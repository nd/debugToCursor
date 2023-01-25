package dbg;

import com.intellij.execution.*;
import com.intellij.execution.runToolbar.RunToolbarProcessData;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.impl.BreakpointManagerState;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.XDebuggerManagerImpl;
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase;
import com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class DebugToCursor extends AnAction implements DumbAware {

  static final Key<BreakpointManagerState> STATE_KEY = Key.create("dbg.DebugToCursor.BreakpointManagerState");
  static final Key<Boolean> LISTENER_KEY = Key.create("dbg.DebugToCursor.Listener");

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null) {
      return;
    }
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    Document doc = editor.getDocument();
    VirtualFile file = FileDocumentManager.getInstance().getFile(doc);
    if (file == null) {
      return;
    }
    RunnerAndConfigurationSettings selectedConfiguration = getSelectedConfiguration(e);
    if (selectedConfiguration == null) {
      showNotification("No selected run configuration", NotificationType.WARNING);
      return;
    }
    int line = doc.getLineNumber(editor.getCaretModel().getCurrentCaret().getOffset());
    XLineBreakpointType<?> breakpointType = getBreakPointType(e);
    if (breakpointType == null) {
      showNotification("Cannot put a temporary breakpoint in the opened editor", NotificationType.WARNING);
      return;
    }
    XBreakpointManagerImpl bm = (XBreakpointManagerImpl) XDebuggerManager.getInstance(project).getBreakpointManager();
    BreakpointManagerState state = bm.saveState(new BreakpointManagerState());

    ApplicationManager.getApplication().runWriteAction(() -> {
      XBreakpointBase<?, ?, ?>[] breakpoints = bm.getAllBreakpoints();
      bm.addLineBreakpoint(breakpointType, file.getUrl(), line, null, true);
      for (XBreakpointBase<?, ?, ?> b : breakpoints) {
        bm.removeBreakpoint(b);
      }
    });

    if (project.getUserData(LISTENER_KEY) == null) {
      project.getMessageBus().connect().subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
        @Override
        public void processStarted(@NotNull XDebugProcess debugProcess) {
          debugProcess.getSession().addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionStopped() {
              restoreState(debugProcess, project);
            }

            @Override
            public void sessionPaused() {
              restoreState(debugProcess, project);
            }

            private void restoreState(@NotNull XDebugProcess debugProcess, @NotNull Project project) {
              XBreakpointManagerImpl bm = (XBreakpointManagerImpl) XDebuggerManager.getInstance(project).getBreakpointManager();
              ExecutionEnvironment env = ((XDebugSessionImpl) debugProcess.getSession()).getExecutionEnvironment();
              BreakpointManagerState state = env != null ? env.getUserData(STATE_KEY) : null;
              if (state != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                  env.putUserData(STATE_KEY, null);
                  bm.loadState(state);
                });
              }
            }
          });
        }
      });
      project.putUserData(LISTENER_KEY, true);
    }

    run(project, selectedConfiguration, e.getDataContext(), state);
  }

  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    XLineBreakpointType<?> breakPointType = getBreakPointType(e);
    presentation.setEnabled(breakPointType != null && getSelectedConfiguration(e) != null && getDebugExecutor() != null);
    presentation.setIcon(AllIcons.Actions.RestartDebugger);
    presentation.setDescription("Debug to cursor");
    presentation.setText("Debug to Cursor");
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  private @Nullable XLineBreakpointType<?> getBreakPointType(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null) {
      return null;
    }
    Project project = e.getProject();
    if (project == null) {
      return null;
    }
    Document doc = editor.getDocument();
    VirtualFile file = FileDocumentManager.getInstance().getFile(doc);
    if (file == null) {
      return null;
    }
    int line = doc.getLineNumber(editor.getCaretModel().getCurrentCaret().getOffset());
    XLineBreakpointType<?> breakpointType = null;
    XLineBreakpointType<?>[] breakPointTypes = XDebuggerUtil.getInstance().getLineBreakpointTypes();
    for (XLineBreakpointType<?> type : breakPointTypes) {
      if (type.canPutAt(file, line, project)) {
        if (breakpointType == null || type.getPriority() > breakpointType.getPriority()) {
          breakpointType = type;
        }
      }
    }
    return breakpointType;
  }

  private static @Nullable RunnerAndConfigurationSettings getSelectedConfiguration(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    RunManager runManager = project == null ? null : RunManager.getInstanceIfCreated(project);
    return runManager == null ? null : runManager.getSelectedConfiguration();
  }

  private static void run(@NotNull Project project,
                          @NotNull RunnerAndConfigurationSettings settings,
                          @NotNull DataContext dataContext,
                          @NotNull BreakpointManagerState state) {
    Executor executor = getDebugExecutor();
    if (executor == null) {
      showNotification("Debug executor is not found", NotificationType.WARNING);
      return;
    }
    Consumer<ExecutionEnvironment> envSetup = RunToolbarProcessData.prepareBaseSettingCustomization(settings, env -> {
      env.putUserData(STATE_KEY, state);
    });
    ExecutorRegistryImpl.RunnerHelper.runSubProcess(project, settings.getConfiguration(), settings, dataContext, executor, envSetup);
  }

  private static void showNotification(@NotNull String message, @NotNull NotificationType notificationType) {
    XDebuggerManagerImpl.getNotificationGroup().createNotification(message, notificationType);
  }

  @Nullable
  private static Executor getDebugExecutor() {
    return ExecutorRegistry.getInstance().getExecutorById(ToolWindowId.DEBUG);
  }
}
