/** Non-Minecraft child owned by the launcher test; no agents, network or credentials. */
class ProcessIdentityProbe {
    public static void main(String[] args) throws Exception {
        System.out.println(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
        System.out.flush();
        System.in.read();
    }
}
