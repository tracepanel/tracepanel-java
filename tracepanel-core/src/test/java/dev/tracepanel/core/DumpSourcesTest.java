package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a dump's source is in a dev build: any module of the build the project is in, or of a class directory. */
class DumpSourcesTest {
    @TempDir
    Path dir;

    @AfterEach
    void forget() {
        DumpSources.forget();
    }

    private Path write(String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private Optional<String> found(String root, String className, String fileName) {
        return found(root, className, fileName, null, List.of());
    }

    private Optional<String> found(String root, String className, String fileName, Path classes, List<Path> classpath) {
        return DumpSources.find(dir.resolve(root), className, fileName, classes, classpath)
                .map(path -> dir.relativize(path).toString().replace('\\', '/'));
    }

    @Test
    void a_gradle_build_s_included_modules_at_any_depth_from_any_of_its_modules() throws IOException {
        write("shop/settings.gradle.kts", "rootProject.name = \"shop\"\ninclude(\":app\", \":libs:core\")\ninclude(\"tools\")\n");
        write("shop/app/src/main/java/com/shop/App.java", "package com.shop;\nclass App {}\n");
        write("shop/libs/core/src/main/kotlin/com/shop/core/Prices.kt", "package com.shop.core\n");
        write("shop/libs/core/src/test/java/com/shop/core/PricesTest.java", "package com.shop.core;\n");
        write("shop/tools/src/jvmMain/kotlin/com/shop/tools/Cli.kt", "package com.shop.tools\n");
        // Kotlin's file outside its package's directory, in a multiplatform set.
        write("shop/libs/shared/src/commonMain/kotlin/Money.kt", "// money\npackage com.shop.shared\n");
        // Not a module's source: a build's output.
        write("shop/libs/core/build/generated/com/shop/core/Gen.java", "package com.shop.core;\n");

        assertEquals(Optional.of("shop/libs/core/src/main/kotlin/com/shop/core/Prices.kt"),
                found("shop/app", "com.shop.core.PricesKt", "Prices.kt"));
        assertEquals(Optional.of("shop/libs/core/src/test/java/com/shop/core/PricesTest.java"),
                found("shop/app", "com.shop.core.PricesTest", null));
        assertEquals(Optional.of("shop/tools/src/jvmMain/kotlin/com/shop/tools/Cli.kt"),
                found("shop/libs/core", "com.shop.tools.CliKt", "Cli.kt"));
        assertEquals(Optional.of("shop/libs/shared/src/commonMain/kotlin/Money.kt"),
                found("shop", "com.shop.shared.MoneyKt", "Money.kt"));
        assertEquals(Optional.of("shop/app/src/main/java/com/shop/App.java"), found("shop", "com.shop.App$Inner", null));

        assertEquals(Optional.empty(), found("shop/app", "com.shop.core.Gen", null));
        assertEquals(Optional.empty(), found("shop/app", "com.other.Prices", "Prices.kt"));
    }

    @Test
    void a_maven_build_s_modules_from_its_topmost_pom() throws IOException {
        write("mall/pom.xml", "<project><modules><module>services</module><!-- <module>old</module> --></modules></project>");
        write("mall/services/pom.xml", "<project><modules>\n  <module>deep/orders</module>\n</modules></project>");
        write("mall/services/deep/orders/pom.xml", "<project/>");
        write("mall/services/deep/orders/src/main/java/com/mall/orders/Orders.java", "package com.mall.orders;\n");
        write("mall/services/api/src/main/java/com/mall/api/Api.java", "package com.mall.api;\n");

        assertEquals(dir.resolve("mall").toAbsolutePath().normalize(), DumpSources.buildRoot(dir.resolve("mall/services/deep/orders")));
        assertEquals(Optional.of("mall/services/deep/orders/src/main/java/com/mall/orders/Orders.java"),
                found("mall/services/api", "com.mall.orders.Orders", null));
        // A directory with a src no build file names: a module too.
        assertEquals(Optional.of("mall/services/api/src/main/java/com/mall/api/Api.java"),
                found("mall/services/deep/orders", "com.mall.api.Api", null));
    }

    @Test
    void a_class_directory_names_its_module_wherever_the_app_runs_from() throws IOException {
        write("elsewhere/lib/src/main/java/com/lib/Tool.java", "package com.lib;\n");
        Path classes = Files.createDirectories(dir.resolve("elsewhere/lib/build/classes/java/main"));
        write("other/maven/src/test/kotlin/com/other/Check.kt", "package com.other\n");
        Path testClasses = Files.createDirectories(dir.resolve("other/maven/target/test-classes"));
        Files.createDirectories(dir.resolve("runner"));

        assertEquals(dir.resolve("elsewhere/lib").toAbsolutePath().normalize(), DumpSources.module(classes));
        assertEquals(Optional.of("elsewhere/lib/src/main/java/com/lib/Tool.java"),
                found("runner", "com.lib.Tool", null, classes, List.of()));
        assertEquals(Optional.of("other/maven/src/test/kotlin/com/other/Check.kt"),
                found("runner", "com.other.CheckKt", "Check.kt", null, List.of(classes, testClasses)));
        assertEquals(Optional.empty(), found("runner", "com.lib.Tool", null));
    }

    @Test
    void a_file_added_after_the_index_is_found_once_it_is_built_again() throws IOException {
        write("late/settings.gradle", "include ':a'\n");
        write("late/a/src/main/java/com/late/One.java", "package com.late;\n");
        assertEquals(Optional.of("late/a/src/main/java/com/late/One.java"), found("late", "com.late.One", null));

        write("late/a/src/main/java/com/late/Two.java", "package com.late;\n");
        DumpSources.forget();

        assertEquals(Optional.of("late/a/src/main/java/com/late/Two.java"), found("late", "com.late.Two", null));
    }
}
