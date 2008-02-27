/*
 * Christopher Deckers (chrriis@nextencia.net)
 * http://www.nextencia.net
 * 
 * See the file "readme.txt" for information on usage and redistribution of
 * this file, and for a DISCLAIMER OF ALL WARRANTIES.
 */
package chrriis.dj.nativeswing;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.Permission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

import org.eclipse.swt.widgets.Display;

import chrriis.common.Utils;
import chrriis.dj.nativeswing.ui.NativeComponent;

import com.sun.jna.Native;
import com.sun.jna.examples.WindowUtils;

/**
 * @author Christopher Deckers
 */
public class NativeInterfaceHandler {

  private static volatile List<Canvas> canvasList;

  /**
   * This method is not part of the public API!
   */
  public static Canvas[] getCanvas() {
    return canvasList.toArray(new Canvas[0]);
  }
  
  /**
   * This method is not part of the public API!
   */
  public static void addCanvas(Canvas canvas) {
    canvasList.add(canvas);
  }
  
  /**
   * This method is not part of the public API!
   */
  public static void removeCanvas(Canvas canvas) {
    canvasList.remove(canvas);
  }
  
  private static Set<Window> windowSet;
  
  /**
   * This method is not part of the public API!
   */
  public static Window[] getWindows() {
    if(Utils.IS_JAVA_6_OR_GREATER) {
      return Window.getWindows();
    }
    return windowSet == null? new Window[0]: windowSet.toArray(new Window[0]);
  }
  
  private static boolean isInitialized;
  
  private static boolean isInitialized() {
    return isInitialized;
  }
  
  private static void checkInitialized() {
    if(!isInitialized()) {
      throw new IllegalStateException("The native interface handler is not initialized! Please refer to the instructions to set it up properly.");
    }
  }

  public static void init() {
    if(isInitialized()) {
      return;
    }
    isInitialized = true;
    canvasList = new ArrayList<Canvas>();
    // Specific Sun property to prevent heavyweight components from erasing their background.
    System.setProperty("sun.awt.noerasebackground", "true");
    // It seems on Linux this is required to get the component visible.
    System.setProperty("sun.awt.xembedserver", "true");
    // Remove all lightweight windows, to avoid wrong overlaps
    ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
    JPopupMenu.setDefaultLightWeightPopupEnabled(false);
    System.setProperty("JPopupMenu.defaultLWPopupEnabledKey", "false");
    // Create the interface to communicate with the process handling the native side
    messagingInterface = createMessagingInterface();
    // Create window monitor
    Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
      protected Set<Dialog> dialogSet = new HashSet<Dialog>();
      protected volatile Set<Window> blockedWindowSet = new HashSet<Window>();
      protected void adjustNativeComponents() {
        for(int i=canvasList.size()-1; i>=0; i--) {
          final Canvas canvas = canvasList.get(i);
          Component c = canvas;
          if(canvas instanceof NativeComponent) {
            Component componentProxy = ((NativeComponent)canvas).getComponentProxy();
            if(componentProxy != null) {
              c = componentProxy;
            }
          }
          Window embedderWindowAncestor = SwingUtilities.getWindowAncestor(c);
          boolean isBlocked = blockedWindowSet.contains(embedderWindowAncestor);
          final boolean isShowing = c.isShowing();
          if(canvas instanceof NativeComponent) {
            ((NativeComponent)canvas).setShellEnabled(!isBlocked && isShowing);
          }
          boolean hasFocus = canvas.hasFocus();
          if(!isShowing && hasFocus) {
            canvas.transferFocus();
          }
        }
      }
      public void eventDispatched(AWTEvent e) {
        boolean isAdjusting = false;
        switch(e.getID()) {
          case ComponentEvent.COMPONENT_SHOWN:
          case ComponentEvent.COMPONENT_HIDDEN:
            isAdjusting = true;
            break;
        }
        if(!Utils.IS_JAVA_6_OR_GREATER && e.getSource() instanceof Window) {
          if(windowSet == null) {
            windowSet = new HashSet<Window>();
          }
          switch(e.getID()) {
            case WindowEvent.WINDOW_OPENED:
            case ComponentEvent.COMPONENT_SHOWN:
              windowSet.add((Window)e.getSource());
              break;
            case WindowEvent.WINDOW_CLOSED:
            case ComponentEvent.COMPONENT_HIDDEN:
              windowSet.remove(e.getSource());
              break;
          }
        }
        if(e.getSource() instanceof Dialog) {
          switch(e.getID()) {
            case WindowEvent.WINDOW_OPENED:
            case ComponentEvent.COMPONENT_SHOWN:
              dialogSet.add((Dialog)e.getSource());
              break;
            case WindowEvent.WINDOW_CLOSED:
            case ComponentEvent.COMPONENT_HIDDEN:
              dialogSet.remove(e.getSource());
              break;
          }
          switch(e.getID()) {
            case WindowEvent.WINDOW_OPENED:
            case WindowEvent.WINDOW_CLOSED:
            case ComponentEvent.COMPONENT_SHOWN:
            case ComponentEvent.COMPONENT_HIDDEN:
              blockedWindowSet.clear();
              for(Dialog dialog: dialogSet) {
                // TODO: consider modal excluded and other modality types than simple parent blocking.
                if(dialog.isVisible() && dialog.isModal()) {
                  blockedWindowSet.add(dialog.getOwner());
                }
              }
              isAdjusting = true;
              break;
          }
        }
        if(isAdjusting) {
          adjustNativeComponents();
        }
      }
    }, WindowEvent.WINDOW_EVENT_MASK | ComponentEvent.COMPONENT_EVENT_MASK);
  }
  
  private static MessagingInterface createMessagingInterface() {
    int port = Integer.parseInt(System.getProperty("dj.nativeswing.port", "-1"));
    if(port <= 0) {
      ServerSocket serverSocket;
      try {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(false);
        serverSocket.bind(new InetSocketAddress(0));
      } catch(IOException e) {
        throw new RuntimeException(e);
      }
      port = serverSocket.getLocalPort();
      try {
        serverSocket.close();
      } catch(IOException e) {
      }
    }
    String javaHome = System.getProperty("java.home");
    ProcessBuilder builder = new ProcessBuilder();
    List<String> classPathList = new ArrayList<String>();
    String classPath = System.getProperty("java.class.path");
    if(classPath != null && classPath.length() != 0) {
      classPathList.add(classPath);
    }
    Class<?>[] classList = new Class[] {
        NativeInterfaceHandler.class,
        Display.class,
        Native.class,
        WindowUtils.class,
    };
    for(Class<?> clazz: classList) {
      File clazzClassPath = Utils.getClassPathFile(clazz);
      if(clazzClassPath != null) {
        classPathList.add(clazzClassPath.getAbsolutePath());
      }
    }
    if(classPathList.isEmpty()) {
      throw new IllegalStateException("Cannot find a suitable classpath to spawn VM!");
    }
    StringBuilder sb = new StringBuilder();
    String pathSeparator = System.getProperty("path.separator");
    for(int i=0; i<classPathList.size(); i++) {
      if(i > 0) {
        sb.append(pathSeparator);
      }
      sb.append(classPathList.get(i));
    }
    String[] candidateBinaries = new String[] {
        new File(javaHome, "bin/java").getAbsolutePath(),
        new File("/usr/lib/java").getAbsolutePath(),
        "java",
    };
    Process p = null;
    for(String candidateBinary: candidateBinaries) {
      List<String> argList = new ArrayList<String>();
      argList.add(candidateBinary);
      argList.add("-classpath");
      argList.add(sb.toString());
      argList.add(NativeInterfaceHandler.class.getName());
      argList.add(String.valueOf(port));
      builder.command(argList);
      try {
        p = builder.start();
        break;
      } catch(IOException e) {
      }
    }
    if(p == null) {
      throw new IllegalStateException("Failed to spawn the VM!");
    }
    connectStream(System.err, p.getErrorStream());
    connectStream(System.out, p.getInputStream());
    Socket socket = null;
    for(int i=19; i>=0; i--) {
      try {
        socket = new Socket(InetAddress.getLocalHost(), port);
        break;
      } catch(IOException e) {
        if(i == 0) {
          throw new RuntimeException(e);
        }
      }
      try {
        Thread.sleep(100);
      } catch(Exception e) {
      }
    }
    if(socket == null) {
      p.destroy();
      throw new IllegalStateException("Failed to connect to spawn VM!");
    }
    return new MessagingInterface(socket, false) {
      @Override
      protected void asyncUIExec(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
      }
      @Override
      public boolean isUIThread() {
        return SwingUtilities.isEventDispatchThread();
      }
    };
  }
  
  private static void connectStream(final PrintStream out, InputStream in) {
    final BufferedInputStream bin = new BufferedInputStream(in);
    Thread streamThread = new Thread("NativeSwing Stream Connector") {
      @Override
      public void run() {
        try {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          String lineSeparator = System.getProperty("line.separator");
          byte lastByte = (byte)lineSeparator.charAt(lineSeparator.length() - 1);
          boolean addMessage = true;
          byte[] bytes = new byte[1024];
          for(int i; (i=bin.read(bytes)) != -1; ) {
            baos.reset();
            for(int j=0; j<i; j++) {
              byte b = bytes[j];
              if(addMessage) {
                baos.write("NativeSwing: ".getBytes());
              }
              addMessage = b == lastByte;
              baos.write(b);
            }
            final byte[] byteArray = baos.toByteArray();
            // Flushing directly to the out stream freezes in Webstart.
            SwingUtilities.invokeLater(new Runnable() {
              public void run() {
                try {
                  out.write(byteArray);
                } catch(Exception e) {
                  e.printStackTrace();
                }
              }
            });
          }
        } catch(Exception e) {
        }
      }
    };
    streamThread.setDaemon(true);
    streamThread.start();
  }
  
  private NativeInterfaceHandler() {}
  
  private static volatile MessagingInterface messagingInterface;

  static MessagingInterface getMessagingInterface() {
    return messagingInterface;
  }
  
  public static Object syncExec(final Message message) {
    checkInitialized();
    return messagingInterface.syncExec(message);
  }
  
  public static void asyncExec(final Message message) {
    checkInitialized();
    messagingInterface.asyncExec(message);
  }
  
  public static void setPreferredLookAndFeel() {
    try {
      String systemLookAndFeelClassName = UIManager.getSystemLookAndFeelClassName();
      if(!"com.sun.java.swing.plaf.gtk.GTKLookAndFeel".equals(systemLookAndFeelClassName)) {
        UIManager.setLookAndFeel(systemLookAndFeelClassName);
      }
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
  
  private static Display display;
  
  /**
   * Only possible when in the native context
   */
  public static Display getDisplay() {
    return display;
  }
  
  public static boolean isNativeSide() {
    return display != null;
  }
  
  public static void checkUIThread() {
    messagingInterface.checkUIThread();
  }
  
  private static class CMJ_getProperties extends CommandMessage {
    @Override
    public Object run() throws Exception {
      return System.getProperties();
    }
  }
  
  public static void main(String[] args) throws Exception {
    isInitialized = true;
    int port = Integer.parseInt(args[0]);
    ServerSocket serverSocket = null;
    for(int i=19; i>=0; i--) {
      try {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        break;
      } catch(IOException e) {
        if(i == 0) {
          throw e;
        }
      }
      try {
        Thread.sleep(100);
      } catch(Exception e) {
      }
    }
    final ServerSocket serverSocket_ = serverSocket;
    Thread shutdownThread = new Thread("NativeSwing Shutdown") {
      @Override
      public void run() {
        try {
          sleep(5000);
        } catch(Exception e) {
        }
        if(messagingInterface == null) {
          try {
            serverSocket_.close();
          } catch(Exception e) {
          }
        }
      }
    };
    shutdownThread.setDaemon(true);
    shutdownThread.start();
    // We set up a new security manager to track exit calls.
    // When this happens, we dispose native resources to avoid freezes.
    try {
      System.setSecurityManager(new SecurityManager() {
        protected SecurityManager securityManager = System.getSecurityManager();
        @Override
        public void checkExit(int status) {
          super.checkExit(status);
          for(StackTraceElement stackTraceElement: Thread.currentThread().getStackTrace()) {
            String className = stackTraceElement.getClassName();
            String methodName = stackTraceElement.getMethodName();
            if("java.lang.Runtime".equals(className) && ("exit".equals(methodName) || "halt".equals(methodName)) || "java.lang.System".equals(className) && "exit".equals(methodName)) {
              System.err.println("cleanup");
              //TODO: perform cleanup
              break;
            }
          }
        }
        @Override
        public void checkPermission(Permission perm) {
          if(securityManager != null) {
            securityManager.checkPermission(perm);
          }
        }
      });
    } catch(Exception e) {
      e.printStackTrace();
    }
    Socket socket;
    try {
      socket = serverSocket.accept();
    } catch(Exception e) {
      throw new IllegalStateException("The native side did not receive an incoming connection!");
    }
    display = new Display();
    final Thread uiThread = Thread.currentThread();
    messagingInterface = new MessagingInterface(socket, true) {
      @Override
      protected void asyncUIExec(Runnable runnable) {
        display.asyncExec(runnable);
      }
      @Override
      public boolean isUIThread() {
        return Thread.currentThread() == uiThread;
      }
    };
    Properties systemProperties = System.getProperties();
    Properties properties = (Properties)new CMJ_getProperties().syncExec();
    for(Object o: properties.keySet()) {
      if(!systemProperties.containsKey(o)) {
        try {
          System.setProperty((String)o, properties.getProperty((String)o));
        } catch(Exception e) {
        }
      }
    }
    while(display != null && !display.isDisposed()) {
      if(!display.readAndDispatch()) {
        display.sleep();
      }
    }
  }
  
}
