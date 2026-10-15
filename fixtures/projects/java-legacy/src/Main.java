package app;

import com.sun.tools.attach.VirtualMachine;
import java.security.KeyStore;
import javax.script.ScriptEngineManager;
import javax.validation.constraints.NotNull;

public class Main {
    private final Integer lock = 0;

    @NotNull
    public String name;

    public void run(String pid) throws Exception {
        Object engine = new ScriptEngineManager().getEngineByName("nashorn");
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        VirtualMachine vm = VirtualMachine.attach(pid);
        synchronized (lock) {
            vm.loadAgent("agent.jar");
        }
    }
}
