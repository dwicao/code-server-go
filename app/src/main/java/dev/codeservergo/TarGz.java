package dev.codeservergo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Minimal streaming {@code .tar.gz} extractor for the runtime payload (rootfs + code-server).
 *
 * <p>Deliberately dependency-free: no AndroidX, no commons-compress. It understands ustar, GNU
 * long names, pax {@code path} records, directories, symlinks, hardlinks and the file mode bits
 * (so {@code bin/bash} stays executable). Anything else is skipped rather than failing the install.
 */
final class TarGz {

    interface Progress {
        void onBytes(long done);
    }

    private static final int BLOCK = 512;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private TarGz() {
    }

    static void extract(InputStream raw, File destDir, Progress progress) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create " + destDir);
        }
        String destPath = destDir.getCanonicalPath();
        GZIPInputStream gz = new GZIPInputStream(raw, 64 * 1024);

        long done = 0;
        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        List<String[]> deferred = new ArrayList<>();

        while (true) {
            int read = readFully(gz, header);
            if (read <= 0) break;
            if (read < BLOCK) throw new IOException("truncated tar header");

            boolean empty = true;
            for (byte b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) break; // end of archive

            String name = string(header, 0, 100);
            String prefix = string(header, 345, 155);
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8);
            char type = (char) (header[156] & 0xff);
            String linkName = string(header, 157, 100);

            if (type == 'K') { // GNU long link name (for hard/sym links)
                longLink = trimNul(readBlock(gz, size));
                skipFully(gz, padded(size) - size);
                done += padded(size);
                continue;
            }
            if (type == 'L') { // GNU long name
                longName = trimNul(readBlock(gz, size));
                skipFully(gz, padded(size) - size);
                done += padded(size);
                continue;
            }
            if (type == 'x' || type == 'X') { // pax extended header for the next entry
                String body = readBlock(gz, size);
                skipFully(gz, padded(size) - size);
                done += padded(size);
                String p = paxValue(body, "path");
                if (p != null) paxPath = trimNul(p);
                String l = paxValue(body, "linkpath");
                if (l != null) paxLink = trimNul(l);
                continue;
            }
            if (type == 'g') { // pax global header, ignore content
                skipFully(gz, padded(size));
                done += padded(size);
                continue;
            }

            if (longName != null) {
                name = longName;
                longName = null;
            } else if (paxPath != null) {
                name = paxPath;
                paxPath = null;
            } else if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            if (longLink != null) {
                linkName = longLink;
                longLink = null;
            } else if (paxLink != null) {
                linkName = paxLink;
                paxLink = null;
            }

            if (name.startsWith("./")) name = name.substring(2);
            while (name.startsWith("/")) name = name.substring(1);
            if (name.isEmpty() || name.contains("..")) {
                skipFully(gz, padded(size));
                done += padded(size);
                continue;
            }

            File out = new File(destDir, name);
            if (!out.getCanonicalPath().startsWith(destPath)) {
                skipFully(gz, padded(size));
                done += padded(size);
                continue;
            }

            switch (type) {
                case '5': // directory
                    out.mkdirs();
                    skipFully(gz, padded(size));
                    break;
                case '2': { // symlink
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    deleteQuietly(out);
                    try {
                        Files.createSymbolicLink(out.toPath(),
                                new File(linkName).toPath());
                    } catch (Exception ignored) {
                        // Some filesystems cannot hold symlinks; an empty file is harmless here.
                    }
                    skipFully(gz, padded(size));
                    break;
                }
                case '1': { // hardlink
                    File target = new File(destDir, linkName);
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    deleteQuietly(out);
                    if (target.exists()) {
                        linkOrCopy(target, out);
                    } else {
                        // The target may simply appear later in the archive.
                        deferred.add(new String[]{linkName, name});
                    }
                    skipFully(gz, padded(size));
                    break;
                }
                case '0':
                case 0:
                case '7': { // regular file
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    long written = copy(gz, out, size, done, progress);
                    done += written;
                    applyMode(out, mode);
                    break;
                }
                default:
                    skipFully(gz, padded(size));
                    break;
            }
            done += padded(type == '0' || type == 0 || type == '7' ? 0 : size);
            if (progress != null) progress.onBytes(done);
        }
        // Second pass for hardlinks whose target came later in the stream.
        for (String[] entry : deferred) {
            File target = new File(destDir, entry[0]);
            File out = new File(destDir, entry[1]);
            if (target.exists() && !out.exists()) {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                linkOrCopy(target, out);
            }
        }
        gz.close();
    }

    /** Hard links are not always allowed in app storage; copying is equivalent here. */
    private static void linkOrCopy(File target, File out) {
        try {
            Files.createLink(out.toPath(), target.toPath());
            return;
        } catch (Exception ignored) {
        }
        FileInputStream in = null;
        FileOutputStream fos = null;
        try {
            in = new FileInputStream(target);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Exception ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static long copy(InputStream in, File out, long size, long already,
                             Progress progress) throws IOException {
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            long left = size;
            long written = 0;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = in.read(buf, 0, want);
                if (n < 0) throw new IOException("unexpected end of archive in " + out.getName());
                fos.write(buf, 0, n);
                left -= n;
                written += n;
                if (progress != null && written % (1024 * 1024) < buf.length) {
                    progress.onBytes(already + written);
                }
            }
            skipFully(in, padded(size) - size);
            return size;
        } finally {
            fos.close();
        }
    }

    private static void applyMode(File f, int mode) {
        boolean exec = (mode & 0100) != 0;
        f.setExecutable(exec, false);
        f.setReadable(true, false);
        f.setWritable((mode & 0200) != 0, true);
    }

    private static String paxValue(String body, String key) {
        int i = 0;
        while (i < body.length()) {
            int space = body.indexOf(' ', i);
            if (space < 0) break;
            int len;
            try {
                len = Integer.parseInt(body.substring(i, space).trim());
            } catch (Exception e) {
                break;
            }
            if (len <= 0 || i + len > body.length()) break;
            String record = body.substring(space + 1, i + len);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String k = record.substring(0, eq);
                String v = record.substring(eq + 1).replace("\n", "");
                if (key.equals(k)) return v;
            }
            i += len;
        }
        return null;
    }

    private static String trimNul(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == 0 || c == '\n' || c == '\r') end--;
            else break;
        }
        return s.substring(0, end);
    }

    private static String readBlock(InputStream in, long size) throws IOException {
        int total = (int) size;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(16, total));
        byte[] buf = new byte[Math.max(16, total)];
        int left = total;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) throw new IOException("unexpected end of archive");
            bos.write(buf, 0, n);
            left -= n;
        }
        return new String(bos.toByteArray(), UTF8);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) return total;
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) return;
            left -= n;
        }
    }

    private static long padded(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? size : size + (BLOCK - rem);
    }

    private static String string(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, UTF8);
    }

    private static long octal(byte[] buf, int off, int len) {
        String s = string(buf, off, len).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void deleteQuietly(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
        }
    }
}
