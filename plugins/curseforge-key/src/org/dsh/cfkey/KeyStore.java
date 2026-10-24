package org.dsh.cfkey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Reads and writes the user's CurseForge API key.
 *
 * <h2>Why the key is obfuscated rather than left in plain text</h2>
 * <p>An API key is a credential tied to the account that registered it. Writing it to a
 * readable file invites it being copied into a screenshot, a bug report, or a synced
 * folder. It is therefore stored Base64-encoded with a per-install salt and a machine
 * component, which stops casual reading and stops the file being useful if copied to
 * another machine.</p>
 *
 * <p>This is obfuscation, not encryption: anyone determined, with the source, can reverse
 * it. That is an acceptable trade-off because the key never leaves the user's machine
 * except to CurseForge itself, and it must be sent to CurseForge in the clear anyway. Real
 * protection would need a secret the launcher does not have, and pretending otherwise would
 * be worse than saying so.</p>
 *
 * <h2>File format</h2>
 * <pre>
 *   v1:&lt;base64(salt)&gt;:&lt;base64(obfuscated key)&gt;
 * </pre>
 */
final class KeyStore {

    /// The file the key lives in, inside the plugin's data directory.
    static final String FILE_NAME = "curseforge-key.txt";

    /// The current format marker. A file with any other marker is ignored.
    private static final String MARKER = "v1";

    private final Path file;

    /// Creates a store backed by a file in the given directory.
    ///
    /// @param dataDirectory the plugin's data directory
    KeyStore(Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    /// The path of the file this store reads and writes.
    Path path() {
        return file;
    }

    /// Loads the stored key.
    ///
    /// @return the key, or null when none is stored or the file cannot be understood
    String load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            String[] parts = content.split(":");
            if (parts.length != 3 || !MARKER.equals(parts[0])) {
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] obscured = Base64.getDecoder().decode(parts[2]);
            byte[] key = Obfuscation.apply(obscured, salt);
            String value = new String(key, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException | IllegalArgumentException e) {
            // A corrupt or foreign file is treated as "no key" rather than an error: the
            // user can simply enter it again.
            return null;
        }
    }

    /// Saves the key, replacing any previous one.
    ///
    /// @param key the key to store; must not be blank
    /// @throws IOException when the file cannot be written
    void save(String key) throws IOException {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] obscured = Obfuscation.apply(key.getBytes(StandardCharsets.UTF_8), salt);

        String content = MARKER + ":"
                + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(obscured) + "\n";

        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        restrictPermissions();
    }

    /// Forgets the stored key.
    ///
    /// @return true when a file was removed
    boolean clear() {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    /// True when a key is stored and can be read back.
    boolean hasKey() {
        return load() != null;
    }

    /// Tries to make the file owner-only.
    ///
    /// <p>Best effort: the POSIX permission API is unavailable on Windows, where the file
    /// inherits the directory's ACLs anyway, and a failure here must not lose the key that
    /// was just written.</p>
    private void restrictPermissions() {
        try {
            var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem; nothing more can be done here.
        }
    }

    /// The reversible scramble applied to the key.
    ///
    /// <p>A keystream derived from the salt and a constant machine component, XORed into
    /// the data. Deterministic for the same machine and salt, so the key can be read back;
    /// different on another machine, so a copied file is useless.</p>
    private static final class Obfuscation {

        private Obfuscation() {
        }

        /// Scrambles or unscrambles {@code data} with {@code salt}.
        ///
        /// <p>XOR is its own inverse, so the same call both encrypts and decrypts.</p>
        static byte[] apply(byte[] data, byte[] salt) {
            byte[] stream = keystream(salt, data.length);
            byte[] result = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                result[i] = (byte) (data[i] ^ stream[i]);
            }
            return result;
        }

        /// The keystream for {@code length} bytes.
        private static byte[] keystream(byte[] salt, int length) {
            byte[] machine = machineComponent();
            byte[] seed = new byte[salt.length + machine.length];
            System.arraycopy(salt, 0, seed, 0, salt.length);
            System.arraycopy(machine, 0, seed, salt.length, machine.length);

            byte[] stream = new byte[length];
            long state = 0xcbf29ce484222325L;
            for (byte b : seed) {
                state ^= (b & 0xff);
                state *= 0x100000001b3L;
            }
            for (int i = 0; i < length; i++) {
                // xorshift64*: cheap, deterministic, and good enough to defeat `cat`.
                state ^= state >>> 12;
                state ^= state << 25;
                state ^= state >>> 27;
                stream[i] = (byte) (state * 0x2545F4914F6CDD1DL >>> 56);
            }
            return stream;
        }

        /// Something stable about this machine, so the file does not travel well.
        private static byte[] machineComponent() {
            StringBuilder identity = new StringBuilder();
            identity.append(System.getProperty("os.name", ""));
            identity.append('|').append(System.getProperty("os.arch", ""));
            identity.append('|').append(System.getProperty("user.name", ""));
            identity.append('|').append(System.getenv().getOrDefault("COMPUTERNAME", ""));
            identity.append('|').append(System.getenv().getOrDefault("HOSTNAME", ""));
            identity.append("|hmcl-curseforge-key");
            return identity.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
