/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.apache.commons.lang.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
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

    public static void main(final String[] args) {
        final boolean validInit = args.length > 1;
        final String dbPath = validInit ? args[0] : null;
        final String dbName = validInit ? args[1] : null;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final CubaHSQLDBServer monitor = new CubaHSQLDBServer();
                monitor.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                monitor.setLocationRelativeTo(null);
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
                    String argStr = StringUtils.join(args, ' ');
                    monitor.setStatus(String.format("Invalid usage (args: '%s')\na", argStr));
                }
            }
        });
    }

    private CubaHSQLDBServer() {
        Font monospaced = Font.decode("monospaced");

        statusArea = new JTextArea(2, 80);
        statusArea.setFont(monospaced);
        statusArea.setMargin(new Insets(5, 5, 5, 5));
        exceptionArea = new JTextArea(26, 80);
        exceptionArea.setFont(monospaced);
        exceptionArea.setMargin(new Insets(5, 5, 5, 5));
        JPanel exceptionWrapperContainer = new JPanel();
        exceptionWrapperContainer.setLayout(new BorderLayout(0, 0));
        exceptionWrapperContainer.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
        exceptionWrapperContainer.add(exceptionArea);
        JPanel statusWrapperContainer = new JPanel();
        statusWrapperContainer.setLayout(new BorderLayout(0, 0));
        statusWrapperContainer.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
        statusWrapperContainer.add(statusArea);
        addCopyPopup(statusArea);
        addCopyPopup(exceptionArea);

        exceptionBox = new JPanel();
        LayoutBuilder.create(exceptionBox, BoxLayout.Y_AXIS)
                .addSpace(5)
                .addComponent(exceptionWrapperContainer);

        LayoutBuilder.create(this.getContentPane(), BoxLayout.X_AXIS)
                .addSpace(5)
                .addContainer(BoxLayout.Y_AXIS)
                .addSpace(5)
                .addComponent(statusWrapperContainer)
                .addComponent(exceptionBox)
                .addSpace(5)
                .returnToParent()
                .addSpace(5);

        statusArea.setEditable(false);
        exceptionArea.setEditable(false);
        exceptionBox.setVisible(false);
        exceptionArea.setBackground(new Color(255, 255, 212));
        this.pack();
        this.setResizable(false);
        this.setTitle("HSQLDB Server");
    }

    private void addCopyPopup(final JTextArea source) {
        final JPopupMenu popup = new JPopupMenu();
        popup.add(new AbstractAction("Copy to clipboard") {
            @Override
            public void actionPerformed(ActionEvent e) {
                StringSelection contents = new StringSelection(source.getText());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, contents);
            }
        });
        source.add(popup);
        source.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(source, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(source, e.getX(), e.getY());
                }
            }
        });
    }

    public void setStatus(String status) {
        this.statusArea.setEditable(true);
        setTextPreserveSize(this.statusArea, status);
        this.statusArea.setEditable(false);
    }

    public void setException(Throwable exception) {
        this.exceptionBox.setVisible(true);
        this.exceptionArea.setEditable(true);
        if (exception != null) {
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            exception.printStackTrace(writer);
            setTextPreserveSize(this.exceptionArea, buffer.toString());
        } else {
            this.exceptionArea.setText(null);
        }
        this.exceptionArea.setEditable(false);
        this.pack();
    }

    private void setTextPreserveSize(JTextArea target, String text) {
        Dimension size = target.getPreferredSize();
        target.setText(text);
        target.setPreferredSize(size);
    }

    public HSQLServer startServer(String dbPath, String dbName) {
        try {
            Class<?> serverClass = Class.forName(SERVER_CLASS);
            HSQLServer server = ServerObjectProxy.newInstance(serverClass);
            server.setDaemon(true);
            server.setDatabaseName(0, dbName);
            server.setDatabasePath(0, getDbPath(dbPath, dbName));
            server.start();

            String format = String.format("Status: %s Port: %s\nDB name: '%s' DB path: '%s'",
                    "%s", server.getPort(), dbName, dbPath);
            ServerStatusChecker checker = new ServerStatusChecker(this, server, format);
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

    private String getDbPath(String dbPath, String dbName) {
        File dbDir = new File(dbPath, dbName);
        return new File(dbDir, dbName).getAbsolutePath();
    }

    private JTextArea statusArea;
    private JTextArea exceptionArea;
    private JPanel exceptionBox;

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

        private ServerStatusChecker(CubaHSQLDBServer monitor, HSQLServer server, String statusFormat) {
            this.monitor = monitor;
            this.server = server;
            this.statusFormat = statusFormat;
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
                        monitor.setStatus(String.format(statusFormat, serverStatuses.get(state)));
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
        private String statusFormat;
    }
}
