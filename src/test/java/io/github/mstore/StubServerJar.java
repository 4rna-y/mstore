package io.github.mstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * {@link StubServer} を実行可能 jar に固める。
 *
 * <p>Supervisor は {@code java -jar <jar> nogui} で子プロセスを起こすので、テストからも
 * 「jar」の形で渡す必要がある。テストのクラスパスにある .class をそのまま詰め直している。
 */
final class StubServerJar {

    private static final String CLASS_ENTRY = "io/github/mstore/StubServer.class";

    private StubServerJar() {
    }

    /** 指定の場所にスタブ jar を書き出してパスを返す。 */
    static Path writeTo(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, StubServer.class.getName());

        Files.createDirectories(jar.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest);
                InputStream classFile = StubServer.class.getResourceAsStream("StubServer.class")) {
            if (classFile == null) {
                throw new IllegalStateException(CLASS_ENTRY + " がテストのクラスパスに見つかりません");
            }
            jarOut.putNextEntry(new JarEntry(CLASS_ENTRY));
            classFile.transferTo(jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }
}
