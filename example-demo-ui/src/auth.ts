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
  // The login screen offers a fresh account each time (so you can switch identities easily).
  // 'login' forces the Keycloak login form even if a Keycloak session cookie exists.
  prompt: 'login',
})

export type AuthUser = User

/** Begin the redirect to Keycloak's login page. */
export function login(): Promise<void> {
  return userManager.signinRedirect()
}

/** Complete the redirect callback (called once on app load when ?code= is present). */
export function completeLogin(): Promise<AuthUser> {
  return userManager.signinRedirectCallback()
}

/** End the session (clears local tokens + Keycloak SSO session). */
export function logout(): Promise<void> {
  return userManager.signoutRedirect()
}

/** The current user, or null if not signed in / expired. */
export async function currentUser(): Promise<AuthUser | null> {
  const user = await userManager.getUser()
  if (!user || user.expired) return null
  return user
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
