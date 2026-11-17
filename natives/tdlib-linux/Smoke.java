import org.drinkless.tdlib.JsonClient;

/**
 * Loads a libtdjsonjava.so and proves it answers, without any network or account.
 *
 * It runs against the JsonClient class from the tdl-coroutines jar, the one the app uses, so the
 * JNI registration (JNI_OnLoad binds the natives of org.drinkless.tdlib.JsonClient by name and
 * signature) is checked against the real class too. Started by smoke-test.sh:
 * java -cp <classes> Smoke.java <library> <version> <commit>
 */
public final class Smoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: Smoke <libtdjsonjava.so> <expected version> <expected commit>");
            System.exit(2);
        }
        String library = args[0];
        String expectedVersion = args[1];
        String expectedCommit = args[2];

        System.load(library);
        System.out.println("loaded " + library);

        // The jar's JsonClient is a Kotlin class with instance natives whose constructor extracts
        // the jar's own library (TdlSystemLibraryKt.loadSystemLibrary). Allocating it without the
        // constructor keeps that one out, so only the library given here is ever loaded.
        java.lang.reflect.Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        JsonClient td = (JsonClient) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, JsonClient.class);

        td.execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":1}");

        String version = td.execute("{\"@type\":\"getOption\",\"name\":\"version\"}");
        String commit = td.execute("{\"@type\":\"getOption\",\"name\":\"commit_hash\"}");
        System.out.println("td_execute getOption version: " + version);
        System.out.println("td_execute getOption commit_hash: " + commit);
        check(version != null && version.contains("\"" + expectedVersion + "\""), "version is not " + expectedVersion);
        check(commit != null && commit.contains("\"" + expectedCommit + "\""), "commit_hash is not " + expectedCommit);

        // A real client: it starts TDLib's threads and waits for tdlibParameters, so it needs no
        // network. The answer to our own request (matched by @extra) proves send and receive work.
        int client = td.createClientId();
        td.send(client, "{\"@type\":\"getOption\",\"name\":\"version\",\"@extra\":\"smoke\"}");
        String answer = null;
        boolean sawAuthState = false;
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline && answer == null) {
            String event = td.receive(1.0);
            if (event == null) {
                continue;
            }
            if (event.contains("authorizationStateWaitTdlibParameters")) {
                sawAuthState = true;
            }
            if (event.contains("\"@extra\":\"smoke\"")) {
                answer = event;
            }
        }
        System.out.println("client " + client + " answered: " + answer);
        check(answer != null && answer.contains("\"" + expectedVersion + "\""), "the client did not answer getOption");
        check(sawAuthState, "the client never reported authorizationStateWaitTdlibParameters");

        td.send(client, "{\"@type\":\"close\"}");
        long closeDeadline = System.nanoTime() + 20_000_000_000L;
        boolean closed = false;
        while (System.nanoTime() < closeDeadline && !closed) {
            String event = td.receive(1.0);
            closed = event != null && event.contains("authorizationStateClosed");
        }
        check(closed, "the client did not close");
        System.out.println("SMOKE OK: TDLib " + expectedVersion + " loads, executes and runs a client");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("SMOKE FAILED: " + message);
            System.exit(1);
        }
    }
}
