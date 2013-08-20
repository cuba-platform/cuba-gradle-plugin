import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * UI monitor for HSQLDB server instance.
 *
 * @author kozlov
 * @version $Id$
 */
public class CubaHSQLDBServer extends JFrame {

    public static final String SERVER_CLASS = "org.hsqldb.server.Server";

    public static void main(String[] args) {
        final boolean validInit = args.length > 1;
        final String dbPath = validInit ? args[0] : null;
        final String dbName = validInit ? args[1] : null;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final CubaHSQLDBServer monitor = new CubaHSQLDBServer();
                monitor.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                monitor.setVisible(true);

                if (validInit) {
                    final HSQLServer server = monitor.startServer(dbPath, dbName);
                    if (server != null) {
                        monitor.addWindowListener(new WindowAdapter() {
                            @Override
                            public void windowClosing(WindowEvent e) {
                                try {
                                    server.shutdownCatalogs(2 /* NORMAL CLOSE MODE */);
                                } catch (RuntimeException exception) {
                                    // Ignore exceptions from server.
                                }
                            }
                        });
                    }
                } else {
                    monitor.setStatus("Db name or path is not specified, use: java CubaHSQLDBServer <DB Path> <DB Name>");
                }
            }
        });
    }

    private CubaHSQLDBServer() {
        Font monospaced = Font.decode("monospaced");

        statusField = new JTextField(80);
        statusField.setFont(monospaced);
        statusField.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
        exceptionArea = new JTextArea(26, 80);
        exceptionArea.setFont(monospaced);
        exceptionWrapperContainer = new JPanel();

        LayoutBuilder.create(exceptionWrapperContainer, BoxLayout.Y_AXIS)
                .addSpace(5)
                .addComponent(exceptionArea);

        LayoutBuilder.create(this.getContentPane(), BoxLayout.X_AXIS)
                .addSpace(5)
                .addContainer(BoxLayout.Y_AXIS)
                .addSpace(5)
                .addComponent(statusField)
                .addComponent(exceptionWrapperContainer)
                .addSpace(5)
                .returnToParent()
                .addSpace(5);

        statusField.setEditable(false);
        exceptionArea.setEditable(false);
        exceptionWrapperContainer.setVisible(false);
        exceptionArea.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
        exceptionArea.setBackground(new Color(255, 255, 212));
        this.pack();
        this.setResizable(false);
        this.setTitle("HSQLDB Server");
    }

    public void setStatus(String status) {
        this.statusField.setEditable(true);
        this.statusField.setText(status);
        this.statusField.setEditable(false);
    }

    public void setException(Throwable exception) {
        this.exceptionWrapperContainer.setVisible(true);
        this.exceptionArea.setEditable(true);
        if (exception != null) {
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            exception.printStackTrace(writer);

            Dimension size = this.exceptionArea.getPreferredSize();
            this.exceptionArea.setText(buffer.toString());
            this.exceptionArea.setPreferredSize(size);
        } else {
            this.exceptionArea.setText(null);
        }
        this.exceptionArea.setEditable(false);
        this.pack();
    }

    public HSQLServer startServer(String dbPath, String dbName) {
        try {
            Class<?> serverClass = Class.forName(SERVER_CLASS);
            HSQLServer server = ServerObjectProxy.newInstance(serverClass);
            server.setDaemon(true);
            server.setDatabaseName(0, dbName);
            server.setDatabasePath(0, dbPath);
            server.start();

            ServerStatusChecker checker = new ServerStatusChecker(this, server);
            checker.schedule();
            return server;
        } catch (InstantiationException | IllegalAccessException | RuntimeException e) {
            setStatus("Failed to start the server due to: " + e.getClass().getCanonicalName());
            setException(e);
            return null;
        } catch (ClassNotFoundException e) {
            setStatus("Check build configuration to ensure that hsql driver is present in classpath");
            setException(e);
            return null;
        }
    }

    private JTextField statusField;
    private JTextArea exceptionArea;
    private JPanel exceptionWrapperContainer;

    /**
     * org.hsqldb.server.Server real method signatures used for object proxy.
     */
    private interface HSQLServer {
        int getPort();

        int getState();

        Throwable getServerError();

        void setDaemon(boolean daemon);

        void setDatabaseName(int index, String name);

        void setDatabasePath(int index, String path);

        void shutdownCatalogs(int mode);

        void start();
    }

    private static class LayoutBuilder {

        public static LayoutBuilder create(Container container, int axis) {
            return new LayoutBuilder(container, axis, null);
        }

        private LayoutBuilder(Container container, int axis, LayoutBuilder parent) {
            this.axis = axis;
            this.parent = parent;
            this.container = container;
            //noinspection MagicConstant
            this.container.setLayout(new BoxLayout(this.container, axis));
        }

        public LayoutBuilder addSpace(int pixels) {
            Dimension dimension = new Dimension(0, 0);
            switch (axis) {
                case BoxLayout.PAGE_AXIS:
                case BoxLayout.LINE_AXIS:
                    if (container.getComponentOrientation().isHorizontal()) {
                        dimension.setSize(pixels, 0);
                    } else {
                        dimension.setSize(0, pixels);
                    }
                    break;
                case BoxLayout.X_AXIS:
                    dimension.setSize(pixels, 0);
                    break;
                case BoxLayout.Y_AXIS:
                    dimension.setSize(0, pixels);
            }
            container.add(Box.createRigidArea(dimension));
            return this;
        }

        public LayoutBuilder addContainer(int axis) {
            JPanel panel = new JPanel();
            container.add(panel);
            return new LayoutBuilder(panel, axis, this);
        }

        public LayoutBuilder addComponent(Component component) {
            container.add(component);
            return this;
        }

        public LayoutBuilder returnToParent() {
            return parent;
        }

        private LayoutBuilder parent;
        private Container container;
        private int axis;
    }

    private static class ServerObjectProxy implements InvocationHandler {

        public static HSQLServer newInstance(Class<?> server) throws IllegalAccessException, InstantiationException {
            return (HSQLServer) Proxy.newProxyInstance(server.getClassLoader(),
                    new Class[]{HSQLServer.class}, new ServerObjectProxy(server));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Method target = methods.get(name);
            if (target == null) {
                target = serverCls.getMethod(name, method.getParameterTypes());
                methods.put(name, target);
            }
            return target.invoke(serverImpl, args);
        }

        private Object serverImpl;
        private Class<?> serverCls;
        private Map<String, Method> methods = new HashMap<>();

        private ServerObjectProxy(Class<?> serverCls) throws IllegalAccessException, InstantiationException {
            this.serverCls = serverCls;
            this.serverImpl = serverCls.newInstance();
        }
    }

    private static class ServerStatusChecker extends TimerTask {

        public void schedule() {
            timer.schedule(this, 1000, 1000);
        }

        private ServerStatusChecker(CubaHSQLDBServer monitor, HSQLServer server) {
            this.monitor = monitor;
            this.server = server;
            this.portInfo = String.format(" Port: %d", server.getPort());
            this.serverStatuses = new HashMap<Integer, String>() {{
                put(0, "SC_DATABASE_SHUTDOWN");
                put(1, "SERVER_STATE_ONLINE");
                put(4, "SERVER_STATE_OPENING");
                put(8, "SERVER_STATE_CLOSING");
                put(16, "SERVER_STATE_SHUTDOWN");
            }};
        }

        @Override
        public void run() {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        int state = server.getState();
                        if (state == 16) {
                            Throwable exception = server.getServerError();
                            if (exception != null) {
                                monitor.setException(exception);
                                timer.cancel();
                            }
                        }
                        monitor.setStatus("Status: " + serverStatuses.get(state) + portInfo);
                    } catch (RuntimeException e) {
                        monitor.setStatus("Runtime exception");
                        monitor.setException(e);
                        timer.cancel();
                    }
                }
            });
        }

        private Timer timer = new Timer(true);
        private HSQLServer server;
        private CubaHSQLDBServer monitor;
        private Map<Integer, String> serverStatuses;
        private String portInfo;
    }
}
