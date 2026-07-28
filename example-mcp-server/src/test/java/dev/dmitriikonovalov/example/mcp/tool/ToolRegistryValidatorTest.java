package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * U2: the registry must describe <strong>exactly</strong> the advertised {@code @McpTool} surface, and any
 * drift fails the context rather than exposing a tool nothing can authorize.
 */
class ToolRegistryValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test // U2 — declarations matching the surface: the context starts
    void startsWhenEveryAdvertisedToolIsDeclared() {
        runner.withUserConfiguration(TwoTools.class, MatchingDeclarations.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test // U2 — the fail-closed edge: an advertised tool with no declaration is never exposed
    void failsStartupWhenAnAdvertisedToolIsUndeclared() {
        runner.withUserConfiguration(TwoTools.class, PartialDeclarations.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("get_catalog")
                        .hasMessageContaining("not exposed"));
    }

    @Test // U2 — the other direction: a declaration describing a tool that does not exist is drift too
    void failsStartupWhenADeclarationHasNoAdvertisedTool() {
        runner.withUserConfiguration(TwoTools.class, PhantomDeclarations.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("delete_everything"));
    }

    @Test // U2 — a blank category fails while the descriptor is being built, naming the tool
    void failsStartupOnAnUnclassifiableDeclaration() {
        runner.withUserConfiguration(TwoTools.class, BlankCategoryDeclarations.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("get_catalog")
                        .hasMessageContaining("category"));
    }

    // --- fixtures -------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class TwoTools {

        @Bean
        ToolBean toolBean() {
            return new ToolBean();
        }
    }

    static class ToolBean {

        @McpTool(name = "list_catalogs", description = "list")
        public String listCatalogs() {
            return "[]";
        }

        @McpTool(name = "get_catalog", description = "get")
        public String getCatalog() {
            return "{}";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MatchingDeclarations {

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(
                    new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low")),
                    new ToolDescriptor("get_catalog", "view", "READ", "catalog", Set.of("low"))));
        }

        @Bean
        ToolRegistryValidator validator(ApplicationContext context, ToolRegistry registry) {
            return new ToolRegistryValidator(context, registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PartialDeclarations {

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(
                    List.of(new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low"))));
        }

        @Bean
        ToolRegistryValidator validator(ApplicationContext context, ToolRegistry registry) {
            return new ToolRegistryValidator(context, registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PhantomDeclarations {

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(
                    new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low")),
                    new ToolDescriptor("get_catalog", "view", "READ", "catalog", Set.of("low")),
                    new ToolDescriptor("delete_everything", "delete", "WRITE", "product", Set.of("high"))));
        }

        @Bean
        ToolRegistryValidator validator(ApplicationContext context, ToolRegistry registry) {
            return new ToolRegistryValidator(context, registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BlankCategoryDeclarations {

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(
                    new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low")),
                    new ToolDescriptor("get_catalog", "view", "", "catalog", Set.of("low"))));
        }

        @Bean
        ToolRegistryValidator validator(ApplicationContext context, ToolRegistry registry) {
            return new ToolRegistryValidator(context, registry);
        }
    }
}
