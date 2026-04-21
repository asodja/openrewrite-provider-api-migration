package org.gradle.rewrite.providerapi;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for the simple list/map mutations: clear→empty, asList→get, indexed-assign→put.
 */
class MapAndListMutationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion().dependsOn(GradleApiStubs.ALL));
    }

    @Test
    void mapPropertyClearBecomesEmpty() {
        rewriteRun(
                s -> s.recipe(new MigrateMapPropertyClear()),
                java(
                        "import org.gradle.api.provider.MapProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(MapProperty<String, Object> sys) {\n" +
                        "        sys.clear();\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.provider.MapProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(MapProperty<String, Object> sys) {\n" +
                        "        sys.empty();\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void listPropertyClearBecomesEmpty() {
        rewriteRun(
                s -> s.recipe(new MigrateListPropertyClear()),
                java(
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(ListProperty<String> args) {\n" +
                        "        args.clear();\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "class Build {\n" +
                        "    void cfg(ListProperty<String> args) {\n" +
                        "        args.empty();\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void asListBecomesGet() {
        rewriteRun(
                s -> s.recipe(new MigrateAsListToGet()),
                java(
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    List<String> cfg(ListProperty<String> args) {\n" +
                        "        return args.asList();\n" +
                        "    }\n" +
                        "}\n",
                        "import org.gradle.api.provider.ListProperty;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    List<String> cfg(ListProperty<String> args) {\n" +
                        "        return args.get();\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }

    @Test
    void clearDoesNotFireOnRegularList() {
        rewriteRun(
                s -> s.recipe(new MigrateListPropertyClear()),
                java(
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "class Build {\n" +
                        "    void cfg() {\n" +
                        "        List<String> l = new ArrayList<>();\n" +
                        "        l.clear();\n" +
                        "    }\n" +
                        "}\n"
                )
        );
    }
}
