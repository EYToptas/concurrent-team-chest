import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class TokenMint {

    static final String PRIVATE_KEY_FILE = "jwt-private.pem";
    static final String PUBLIC_KEY_FILE = "jwt-public.pem";
    static final String SEEDED_USER_PREFIX = "user-";
    static final String JOIN_USER_PREFIX = "join-";
    static final String OPERATOR_USER = "perf-ops";
    static final int MAX_USERS = 999_999;
    private static final int KEY_SIZE = 2048;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private TokenMint() {
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("token-mint: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws IOException, GeneralSecurityException {
        if (args.length < 2) {
            usage();
        }
        Path secretsDir = Path.of(args[0]);
        switch (args[1]) {
            case "keys" -> {
                requireArgs(args, 2);
                KeyPair keys = loadOrCreateKeys(secretsDir);
                System.err.println("Key pair ready: " + secretsDir.resolve(PUBLIC_KEY_FILE)
                        + " (" + ((RSAPublicKey) keys.getPublic()).getModulus().bitLength() + "-bit RSA)");
            }
            case "token" -> {
                requireArgs(args, 3);
                Issuer issuer = Issuer.fromEnvironment(loadOrCreateKeys(secretsDir).getPrivate());
                System.out.println(issuer.sign(requireUserId(args[2])));
            }
            case "perf" -> {
                requireArgs(args, 5);
                int seededUsers = parseCount(args[2], "seeded-users");
                int joinUsers = parseCount(args[3], "join-users");
                KeyPair keys = loadOrCreateKeys(secretsDir);
                writePerfTokens(Issuer.fromEnvironment(keys.getPrivate()), publicKeyFingerprint(keys),
                        seededUsers, joinUsers, Path.of(args[4]));
            }
            default -> usage();
        }
    }

    static KeyPair loadOrCreateKeys(Path dir) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        Path privateFile = dir.resolve(PRIVATE_KEY_FILE);
        KeyPair keys = Files.exists(privateFile) ? load(privateFile) : generate(privateFile);
        writeIfChanged(dir.resolve(PUBLIC_KEY_FILE), pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        return keys;
    }

    private static KeyPair load(Path privateFile) throws IOException, GeneralSecurityException {
        String pem = Files.readString(privateFile, StandardCharsets.US_ASCII);
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new GeneralSecurityException(privateFile + " is not an RSA private key in PKCS#8 format");
        }
        RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }

    private static KeyPair generate(Path privateFile) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        KeyPair keys = generator.generateKeyPair();
        writeAtomically(privateFile, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        try {
            Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            System.err.println("Note: could not restrict the permissions of " + privateFile);
        }
        System.err.println("Created a new " + KEY_SIZE + "-bit RSA key pair in " + privateFile.getParent());
        return keys;
    }

    static String publicKeyFingerprint(KeyPair keys) throws GeneralSecurityException {
        byte[] file = pem("PUBLIC KEY", keys.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    record Issuer(PrivateKey key, String issuer, String audience, Instant issuedAt, Instant expiresAt) {

        static Issuer fromEnvironment(PrivateKey key) {
            Duration ttl = Duration.parse(System.getenv().getOrDefault("TOKEN_TTL", "PT12H"));
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("TOKEN_TTL must be positive");
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            return new Issuer(key, requireEnv("JWT_ISSUER"), requireEnv("JWT_AUDIENCE"), now, now.plus(ttl));
        }

        String sign(String subject) {
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String claims = "{\"iss\":" + json(issuer) + ",\"aud\":[" + json(audience) + "],\"sub\":" + json(subject)
                    + ",\"iat\":" + issuedAt.getEpochSecond() + ",\"exp\":" + expiresAt.getEpochSecond()
                    + ",\"jti\":" + json(UUID.randomUUID().toString()) + "}";
            String signingInput = base64url(header) + "." + base64url(claims);
            try {
                Signature rs256 = Signature.getInstance("SHA256withRSA");
                rs256.initSign(key);
                rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return signingInput + "." + BASE64URL.encodeToString(rs256.sign());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot sign a token", e);
            }
        }

        private static String base64url(String json) {
            return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void writePerfTokens(Issuer issuer, String publicKeySha256, int seededUsers, int joinUsers, Path tokensDir)
            throws IOException {
        Files.createDirectories(tokensDir);
        long started = System.nanoTime();
        writeAtomically(tokensDir.resolve("tokens-seeded.json"), jsonArray(signAll(issuer, SEEDED_USER_PREFIX, seededUsers)));
        writeAtomically(tokensDir.resolve("tokens-join.json"), jsonArray(signAll(issuer, JOIN_USER_PREFIX, joinUsers)));
        writeAtomically(tokensDir.resolve("ops-token.txt"), issuer.sign(OPERATOR_USER));
        writeAtomically(tokensDir.resolve("tokens-meta.json"), "{\"issuer\":" + json(issuer.issuer())
                + ",\"audience\":" + json(issuer.audience()) + ",\"publicKeySha256\":" + json(publicKeySha256)
                + ",\"seededUsers\":" + seededUsers + ",\"joinUsers\":" + joinUsers
                + ",\"seededUserPrefix\":" + json(SEEDED_USER_PREFIX) + ",\"joinUserPrefix\":" + json(JOIN_USER_PREFIX)
                + ",\"expiresAt\":" + json(issuer.expiresAt().toString())
                + ",\"expiresAtEpochSeconds\":" + issuer.expiresAt().getEpochSecond() + "}\n");
        System.err.printf("Signed %d + %d + 1 tokens in %d ms, valid until %s, written to %s%n",
                seededUsers, joinUsers, (System.nanoTime() - started) / 1_000_000, issuer.expiresAt(), tokensDir);
    }

    private static List<String> signAll(Issuer issuer, String prefix, int count) {
        return IntStream.range(0, count).parallel()
                .mapToObj(i -> issuer.sign(prefix + String.format("%06d", i + 1)))
                .toList();
    }

    private static String jsonArray(List<String> tokens) {
        return tokens.stream().map(TokenMint::json).collect(Collectors.joining(",\n", "[\n", "\n]\n"));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void writeIfChanged(Path file, String content) throws IOException {
        if (!Files.exists(file) || !Files.readString(file, StandardCharsets.US_ASCII).equals(content)) {
            writeAtomically(file, content);
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.US_ASCII);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + name + " is required");
        }
        return value;
    }

    private static String requireUserId(String userId) {
        if (userId.isBlank() || userId.length() > 64 || !userId.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException("User id must be 1-64 printable ASCII characters");
        }
        return userId;
    }

    private static int parseCount(String value, String name) {
        int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number: " + value);
        }
        if (count < 0 || count > MAX_USERS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_USERS);
        }
        return count;
    }

    private static void requireArgs(String[] args, int expected) {
        if (args.length != expected) {
            usage();
        }
    }

    private static void usage() {
        System.err.println("""
                Usage: java TokenMint.java <secrets-dir> keys
                       java TokenMint.java <secrets-dir> token <user-id>
                       java TokenMint.java <secrets-dir> perf <seeded-users> <join-users> <tokens-dir>""");
        System.exit(2);
    }
}
