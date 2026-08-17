import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

// Single-origin auth (see infra/README.md "Demo SPA auth"): the browser does its entire
// Authorization Code + PKCE flow against the gateway origin. APISIX proxies Keycloak's /realms/*
// and /resources/* in-network, and Keycloak rewrites all advertised URLs (issuer + endpoints) to
// this same origin — so authority, issuer, and where-we-call all agree. No /etc/hosts, no CORS in
// the packaged demo (the SPA is served through APISIX), and the gateway accepts the resulting token.
//
// In dev the SPA runs on :3000 and Vite proxies /realms + /resources to :9085, so window.origin
// (http://localhost:3000) is a valid authority too — the proxy makes it transparent.
const ORIGIN = window.location.origin
const AUTHORITY = `${ORIGIN}/realms/catalog-demo`

export const userManager = new UserManager({
  authority: AUTHORITY,
  client_id: 'catalog-spa',
  redirect_uri: `${ORIGIN}/`,
  post_logout_redirect_uri: `${ORIGIN}/`,
  response_type: 'code', // Authorization Code + PKCE (public client, no secret)
  scope: 'openid profile email',
  // Token storage — sessionStorage, not localStorage. sessionStorage is per-tab and cleared when the
  // tab closes, which narrows (does NOT eliminate) the blast radius of an XSS on this origin: any
  // same-origin script can still read the bearer + refresh token while the tab is open.
  //   DEMO-ONLY — DO NOT COPY VERBATIM TO PRODUCTION. A production SPA should keep tokens out of
  //   JS-readable web storage entirely: hold the access token in memory, and put the refresh token
  //   behind a Backend-For-Frontend (httpOnly cookie) or a service worker so no script can exfiltrate
  //   it. See example-demo-ui/README.md. Both userStore and stateStore are set explicitly because
  //   oidc-client-ts v3 defaults an unset stateStore to localStorage (which would leak the PKCE
  //   code_verifier / state / nonce there too).
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // We drive token refresh manually / on demand; no silent-renew iframe for the demo.
  automaticSilentRenew: false,
  // NOTE: `prompt: 'login'` deliberately does NOT live here. As a constructor default it would ride
  // on EVERY signin request this manager builds — including the step-up redirect below, where the
  // re-authentication trigger must be `max_age`, not `prompt`. It moved to login() (see there).
})

export type AuthUser = User

/**
 * Begin the redirect to Keycloak's login page.
 *
 * <p>`prompt: 'login'` is passed HERE rather than configured on the manager: the demo's login screen
 * offers a fresh account each time so identities can be switched easily, which is a property of
 * *this* call, not of every signin request. The step-up redirect below deliberately sends no
 * `prompt` at all.
 */
export function login(): Promise<void> {
  return userManager.signinRedirect({ prompt: 'login' })
}

/** Where the user was when the challenge interrupted them — ids only (see stepUp). */
export interface StepUpLocation {
  catalogId: string
  categoryId?: string
}

/** The OIDC `state` the step-up redirect round-trips through Keycloak. */
export interface StepUpState extends StepUpLocation {
  v: 1
  stepUp: true
}

/**
 * Answer a step-up challenge: re-authenticate at Keycloak with what the server asked for, and come
 * back to where the user was.
 *
 * <p><b>`max_age: 0`, always.</b> Zero forces a genuinely fresh authentication independent of the
 * advertised window and of the realm's per-level `loa-max-age` memory, so the resulting `auth_time`
 * is new. Echoing the challenge's own `max_age` would re-authenticate too — the challenge only fires
 * once the token is past `max_age + skew` — measured to produce the *identical* prompt sequence, so
 * `0` simply removes the reasoning. It also satisfies ADR 0030 §7's "the client MUST forward
 * `max_age`" as a strict superset. The infinite-loop failure mode that ADR warns about comes from
 * <b>omitting</b> `max_age`, not from sending zero: without it Keycloak happily answers from the
 * existing SSO session and returns the same stale `auth_time`, so the read 401s again forever (the
 * step-up matrix's E3 measures exactly that).
 *
 * <p><b>The essential `acr` claim</b> goes through `extraQueryParams` because `claims` is not part of
 * oidc-client-ts's `SigninRedirectArgs`. A bare `acr_values` is only a *voluntary* request, which an
 * IdP may silently under-deliver; asking for it as an essential claim is what makes an under-delivery
 * an error instead of a surprise.
 *
 * <p><b>Ids only in the state.</b> The console's `View` embeds whole objects, and those must not
 * round-trip through the IdP: the state travels in a URL, through a third party, and back.
 */
export function stepUp(
  acrValues: string,
  location: StepUpLocation,
): Promise<void> {
  const state: StepUpState = { v: 1, stepUp: true, ...location }
  return userManager.signinRedirect({
    acr_values: acrValues,
    max_age: 0,
    extraQueryParams: {
      claims: JSON.stringify({
        id_token: { acr: { essential: true, values: [acrValues] } },
      }),
    },
    state,
  })
}

/**
 * The step-up location carried back on THIS page load, or null.
 *
 * <p>Load-bearing library behaviour: `User.toStorageString()` omits `state`, so `user.state` exists
 * only on the callback's page load and cannot leak into later ones. That is precisely what makes the
 * passive guard loop-free — <b>do not "fix" it by persisting the state.</b>
 */
export function stepUpStateOf(user: AuthUser): StepUpState | null {
  const state = user.state as Partial<StepUpState> | undefined
  if (!state || state.v !== 1 || state.stepUp !== true || typeof state.catalogId !== 'string') {
    return null
  }
  return {
    v: 1,
    stepUp: true,
    catalogId: state.catalogId,
    ...(typeof state.categoryId === 'string' ? { categoryId: state.categoryId } : {}),
  }
}

/** Complete the redirect callback (called once on app load when ?code= is present). */
export function completeLogin(): Promise<AuthUser> {
  return userManager.signinRedirectCallback()
}

/** End the session (clears local tokens + Keycloak SSO session). */
export function logout(): Promise<void> {
  return userManager.signoutRedirect()
}

/**
 * The current user with a VALID access token — refreshing on demand when it has lapsed. This is
 * the "manually / on demand" renewal the UserManager config promises (no silent-renew iframe):
 * an expired access token + a refresh token → one refresh-grant call to the token endpoint.
 * Returns null when there is no session or the refresh itself fails (SSO idle timeout / revoked)
 * — callers treat that as signed out rather than sending a dead bearer that would 401 anyway.
 */
export async function freshUser(): Promise<AuthUser | null> {
  const user = await userManager.getUser()
  if (!user) return null
  if (!user.expired) return user
  if (!user.refresh_token) return null
  try {
    return await userManager.signinSilent()
  } catch {
    return null
  }
}

/** The current user, or null if not signed in (an expired token is refreshed on demand). */
export function currentUser(): Promise<AuthUser | null> {
  return freshUser()
}

/** Decode the username + realm roles for display. Roles live in the ACCESS token's realm_access
 *  (not the id-token profile), so decode the access token payload. */
export function describeUser(user: AuthUser): { username: string; roles: string[] } {
  const profile = user.profile as Record<string, unknown>
  const username =
    (profile.preferred_username as string) ?? (profile.name as string) ?? (profile.sub as string)
  let roles: string[] = []
  try {
    const payload = JSON.parse(
      atob(user.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')),
    ) as { realm_access?: { roles?: string[] } }
    roles = (payload.realm_access?.roles ?? []).filter((r) => r.startsWith('catalog-'))
  } catch {
    /* token not decodable; show no roles */
  }
  return { username, roles }
}
