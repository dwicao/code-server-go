package dev.codeservergo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Round-trips gzipped tar archives through the extractor.
 *
 * <p>Each test builds a tar by hand in one of the dialects seen in real tarballs (ustar, GNU long
 * names, pax extended records). These are exactly the dialects whose mishandling dropped native
 * modules on device.
 */
public class TarGzTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ByteArrayOutputStream tar;

    @Before
    public void setUp() {
        tar = new ByteArrayOutputStream();
    }

    @Test
    public void extractsFileAndPreservesExecBit() throws Exception {
        byte[] content = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);
        addEntry("hello.sh", '0', 0755, content, null);
        finishTar();

        FileOrDir d = newFolder("out1");
        // noinspection ResultOfMethodCallIgnored
        TarGz.extract(gzipped(tar.toByteArray()), d.f, null);

        Path f = new java.io.File(d.f, "hello.sh").toPath();
        assertEquals("#!/bin/sh\necho hi\n",
                new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        assertTrue("exec bit should be preserved",
                new java.io.File(d.f, "hello.sh").canExecute());
    }

    @Test
    public void extractsDirectoryAndSymlink() throws Exception {
        addEntry("dir/", '5', 0755, new byte[0], null);
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        addEntry("dir/real.txt", '0', 0644, data, null);
        addEntry("dir/link.txt", '2', 0777, new byte[0], "real.txt");
        finishTar();

        FileOrDir d = newFolder("out2");
        TarGz.extract(gzipped(tar.toByteArray()), d.f, null);

        assertEquals("payload",
                new String(Files.readAllBytes(new java.io.File(d.f, "dir/real.txt").toPath()),
                        StandardCharsets.UTF_8));
        Path link = new java.io.File(d.f, "dir/link.txt").toPath();
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("real.txt", Files.readSymbolicLink(link).toString());
    }

    @Test
    public void extractsGnuLongNameOverOneHundredBytes() throws Exception {
        String longName = "some/deeply/nested/path/" + repeat("component/", 20) + "leaf.txt";
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        addEntry("././@LongLink", 'L', 0644, (longName + '\0').getBytes(StandardCharsets.UTF_8),
                null);
        // header name is irrelevant when a GNU long name is pending
        addEntry("short.txt", '0', 0644, content, null);
        finishTar();

        FileOrDir d = newFolder("out3");
        TarGz.extract(gzipped(tar.toByteArray()), d.f, null);

        Path f = new java.io.File(d.f, longName).toPath();
        assertTrue("long-name entry must exist: " + f, Files.exists(f));
        assertArrayEquals(content, Files.readAllBytes(f));
    }

    @Test
    public void extractsPaxLinkpathHardlinkWithTruncatedHeaderField() throws Exception {
        // The real code-server tarball stores long hard-link targets this way. The 100-byte
        // linkname field cannot hold the target, so pax linkpath= wins.
        String target = "some/very/deeply/nested/tree/" + repeat("node_modules/pkg/", 5) +
                "binding/napi-v3/argon2.node";
        byte[] content = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        addEntry(target, '0', 0644, content, null);

        String pax = "linkpath=" + target + "\n";
        // pax records are "<len> <key>=<value>\n" where len counts the whole record,
        // including its own length prefix and trailing newline — iterate to a fixpoint.
        int len = pax.length() + 3; // "\d+ " prefix, then adjust
        while (String.valueOf(len).length() + 1 + pax.length() != len) {
            len = String.valueOf(len).length() + 1 + pax.length();
        }
        String record = len + " " + pax;
        int headerLen = record.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(headerLen == len);
        addEntry("./pax_header", 'x', 0644, record.getBytes(StandardCharsets.UTF_8), null);
        addEntry("build/Release/argon2.node", '1', 0644, new byte[0], "");
        finishTar();

        FileOrDir d = newFolder("out4");
        TarGz.extract(gzipped(tar.toByteArray()), d.f, null);

        Path link = new java.io.File(d.f, "build/Release/argon2.node").toPath();
        assertTrue("missing argon2 hard link at " + link, Files.exists(link));
        assertArrayEquals(content, Files.readAllBytes(link));
    }

    // ------------------------------------------------------------- tar writer

    private String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** Small wrapper so tests can name both the temp dir file and its path conveniently. */
    private static final class FileOrDir {
        final java.io.File f;

        FileOrDir(java.io.File f) {
            this.f = f;
        }
    }

    private void addEntry(String name, char type, int mode, byte[] data, String linkName)
            throws IOException {
        String hName = name;
        String hPrefix = "";
        if (name.length() > 99) {
            // split for ustar "prefix/name" (both fit) or rely on the GNU long-name path
            int slash = name.lastIndexOf('/', 155);
            if (slash > 0 && name.length() - slash - 1 <= 100) {
                hName = name.substring(slash + 1);
                hPrefix = name.substring(0, slash);
            }
        }

        byte[] header = new byte[512];
        writeString(header, 0, 100, hName);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, 1700000000L);
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = (byte) type;
        writeString(header, 157, 100, linkName == null ? "" : linkName);
        writeString(header, 257, 6, "ustar");
        writeString(header, 263, 2, "00");
        writeString(header, 345, 155, hPrefix);
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        writeOctal(header, 148, 8, sum);
        tar.write(header, 0, 512);
        tar.write(data, 0, data.length);
        int rem = (int) (data.length % 512);
        if (rem != 0) tar.write(new byte[512 - rem], 0, 512 - rem);
    }

    private void finishTar() {
        tar.write(new byte[1024], 0, 1024);
    }

    private InputStream gzipped(byte[] tarBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out);
        gz.write(tarBytes);
        gz.finish();
        gz.close();
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static void writeString(byte[] dest, int off, int len, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, dest, off, Math.min(bytes.length, len));
    }

    private static void writeOctal(byte[] dest, int off, int len, long value) {
        String s = Long.toOctalString(value);
        int padLen = len - s.length() - 1;
        for (int i = 0; i < padLen; i++) dest[off + i] = '0';
        byte[] digits = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(digits, 0, dest, off + padLen, digits.length);
        dest[off + padLen + digits.length] = 0;
    }
    private FileOrDir newFolder(String name) {
        try {
            return new FileOrDir(tmp.newFolder(name));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
