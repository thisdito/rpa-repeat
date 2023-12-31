package frontEnd;

import java.awt.AWTException;
import java.awt.SystemTray;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

import core.background.loggers.ActiveWindowInfosLogger;
import core.background.loggers.MousePositionLogger;
import core.config.AbstractRemoteRepeatsClientsConfig;
import core.config.Config;
import core.controller.Core;
import core.controller.CoreProvider;
import core.ipc.IPCServiceManager;
import core.ipc.IPCServiceName;
import core.ipc.repeatClient.PythonIPCClientService;
import core.ipc.repeatClient.repeatPeerClient.RepeatsPeerServiceClientManager;
import core.ipc.repeatServer.processors.TaskProcessorManager;
import core.keyChain.TaskActivation;
import core.keyChain.managers.GlobalEventsManager;
import core.languageHandler.Language;
import core.languageHandler.compiler.AbstractNativeCompiler;
import core.languageHandler.compiler.DynamicCompilationResult;
import core.languageHandler.compiler.DynamicCompilerOutput;
import core.languageHandler.compiler.PythonRemoteCompiler;
import core.languageHandler.compiler.RemoteRepeatsCompiler;
import core.languageHandler.compiler.RemoteRepeatsDyanmicCompilationResult;
import core.recorder.Recorder;
import core.recorder.ReplayConfig;
import core.userDefinedTask.CompositeUserDefinedAction;
import core.userDefinedTask.TaskGroup;
import core.userDefinedTask.TaskInvoker;
import core.userDefinedTask.TaskSourceManager;
import core.userDefinedTask.Tools;
import core.userDefinedTask.UserDefinedAction;
import core.userDefinedTask.internals.ActionExecutor;
import core.userDefinedTask.internals.AggregateTools;
import core.userDefinedTask.internals.DefaultTools;
import core.userDefinedTask.internals.ITools;
import core.userDefinedTask.internals.RemoteRepeatsClientTools;
import core.userDefinedTask.internals.RunActionConfig;
import core.userDefinedTask.internals.SharedVariablesPubSubManager;
import core.userDefinedTask.internals.TaskSourceHistoryEntry;
import globalListener.GlobalListenerHookController;
import staticResources.BootStrapResources;
import utilities.DateUtility;
import utilities.Desktop;
import utilities.FileUtility;
import utilities.Function;
import utilities.StringUtilities;
import utilities.ZipUtility;
import utilities.logging.CompositeOutputStream;
import utilities.logging.LogHolder;

public class MainBackEndHolder {

	private static final Logger LOGGER = Logger.getLogger(MainBackEndHolder.class.getName());

	protected MinimizedFrame trayIcon;
	protected LogHolder logHolder;
	private final ActiveWindowInfosLogger activeWindowInfosLogger;
	private final MousePositionLogger mousePositionLogger;

	protected ScheduledThreadPoolExecutor executor;
	private Thread compiledExecutor;
	private Language compilingLanguage;

	private ReplayConfig replayConfig;
	private RunActionConfig runActionConfig;
	protected Recorder recorder;

	private UserDefinedAction customFunction;

	protected final List<TaskGroup> taskGroups;
	private TaskGroup currentGroup;

	// To allow executing other tasks programmatically.
	private final TaskInvoker taskInvoker;
	private final ActionExecutor actionExecutor;
	protected final GlobalEventsManager keysManager;
	private RepeatsPeerServiceClientManager peerServiceClientManager;

	protected final Config config;
	private final CoreProvider coreProvider;

	protected final UserDefinedAction switchRecord, switchReplay, switchReplayCompiled;
	private boolean isRecording, isReplaying, isRunningCompiledTask;

	private File tempSourceFile;

	public MainBackEndHolder() {
		config = new Config(this);

		if (!SystemTray.isSupported()) {
			LOGGER.warning("System tray is not supported!");
			trayIcon = null;
		} else {
			trayIcon = new MinimizedFrame(BootStrapResources.TRAY_IMAGE, this);
		}
		logHolder = new LogHolder();

		executor = new ScheduledThreadPoolExecutor(10);
		compilingLanguage = Language.MANUAL_BUILD;

		taskGroups = new ArrayList<>();

		peerServiceClientManager = new RepeatsPeerServiceClientManager();
		coreProvider = new CoreProvider(config, peerServiceClientManager);
		taskInvoker = new TaskInvoker(coreProvider, taskGroups);
		actionExecutor = new ActionExecutor(coreProvider);
		keysManager = new GlobalEventsManager(config, coreProvider, actionExecutor);
		activeWindowInfosLogger = new ActiveWindowInfosLogger(coreProvider);
		mousePositionLogger = new MousePositionLogger(coreProvider);
		replayConfig = ReplayConfig.of();
		runActionConfig = RunActionConfig.of();
		recorder = new Recorder(coreProvider);

		switchRecord = new UserDefinedAction() {
			@Override
			public void action(Core controller) throws InterruptedException {
				switchRecord();
			}
		};

		switchReplay = new UserDefinedAction() {
			@Override
			public void action(Core controller) throws InterruptedException {
				switchReplay();
			}
		};

		switchReplayCompiled = new UserDefinedAction() {
			@Override
			public void action(Core controller) throws InterruptedException {
				switchRunningCompiledAction();
			}
		};

		TaskProcessorManager.setProcessorIdentifyCallback(new Function<Language, Void>(){
			@Override
			public Void apply(Language language) {
				recompiledNativeTasks(language);
				return null;
			}
		});
	}

	protected void initializeLogging() {
		// Change stdout and stderr to also copy content to the logHolder.
		System.setOut(new PrintStream(CompositeOutputStream.of(logHolder, System.out)));
		System.setErr(new PrintStream(CompositeOutputStream.of(logHolder, System.err)));

		// Once we've updated stdout and stderr, we need to re-register the ConsoleHandler of the root
		// logger because it was only logging to the old stderr which we just changed above.
		Logger rootLogger = Logger.getLogger("");
		Handler[] handlers = rootLogger.getHandlers();
		for (Handler handler : handlers) {
			if (handler.getClass().getName().equals(ConsoleHandler.class.getName())) {
				Logger.getLogger("").removeHandler(handler);
			}
		}
		Handler newHandler = new ConsoleHandler();
		newHandler.setFormatter(new SimpleFormatter() {
			private static final String FORMAT = "[%s] %s %s: %s\n";

			@Override
			public synchronized String format(LogRecord lr) {
				Calendar cal = DateUtility.calendarFromMillis(lr.getMillis());
				String base = String.format(FORMAT, DateUtility.calendarToTimeString(cal), lr.getLoggerName(), lr.getLevel().getLocalizedName(), lr.getMessage());
				StringBuilder builder = new StringBuilder(base);
				if (lr.getThrown() != null) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					lr.getThrown().printStackTrace(pw);
					builder.append(sw.toString());
				}
				return builder.toString();
			}
		});
		Logger.getLogger("").addHandler(newHandler);

		// Update the logging level based on the config.
		changeDebugLevel(config.getNativeHookDebugLevel());
	}

	/*************************************************************************************************************/
	/************************************************Config*******************************************************/
	protected void loadConfig(File file) {
		config.loadConfig(file);
		setTaskInvoker();

		if (trayIcon != null) {
			if (config.isUseTrayIcon()) {
				try {
					trayIcon.add();
				} catch (AWTException e) {
					LOGGER.log(Level.WARNING, "Exception when adding tray icon.", e);
				}
			}
		}

		File pythonExecutable = ((PythonRemoteCompiler) (config.getCompilerFactory()).getNativeCompiler(Language.PYTHON)).getPath();
		((PythonIPCClientService)IPCServiceManager.getIPCService(IPCServiceName.PYTHON)).setExecutingProgram(pythonExecutable);

		IPCServiceManager.setBackEnd(this);
	}

	/*************************************************************************************************************/
	/************************************************IPC**********************************************************/
	protected void initiateBackEndActivities() {
		try {
			IPCServiceManager.initiateServices(this);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "IO Exception when launching ipcs.", e);
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Exception when launching ipcs.", e);
		}

		try {
			peerServiceClientManager.startAllClients(true);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Exception when launching clients connecting to peer services.", e);
		}

		recompileRemoteTasks();
	}

	protected void stopBackEndActivities() {
		activeWindowInfosLogger.stop();
		mousePositionLogger.stop();
		executor.shutdown();

		try {
			IPCServiceManager.stopServices();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Unable to stop ipcs.", e);
		}

		try {
			peerServiceClientManager.stopAllClients();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Unable to stop clients to peer services.", e);
		}

		GlobalListenerHookController.of().cleanup();
		SharedVariablesPubSubManager.get().stop();

		LOGGER.info("All backend acitvities terminated!");
	}

	public void scheduleExit(long delayMs) {
		executor.schedule(new Runnable() {
			@Override
			public void run() {
				exit();
			}}, delayMs, TimeUnit.MILLISECONDS);
	}

	private void exit() {
		stopBackEndActivities();

		LOGGER.info("Writing config file...");
		if (!writeConfigFile()) {
			LOGGER.log(Level.SEVERE, "Error saving configuration file.");
			System.exit(2);
		}
		LOGGER.info("Wrote config file.");

		if (trayIcon != null) {
			trayIcon.remove();
		}

		try {
			LOGGER.info("Waiting for main executor to terminate...");
			executor.awaitTermination(3, TimeUnit.SECONDS);
			LOGGER.info("Main executor terminated.");
		} catch (InterruptedException e) {
			LOGGER.log(Level.WARNING, "Interrupted while awaiting backend executor termination.", e);
		}

		LOGGER.info("Terminated all background processes.");

		// Only if really needed.
		// System.exit(0);
	}

	/*************************************************************************************************************/
	/****************************************Main hotkeys*********************************************************/
	protected void configureMainHotkeys() {
		reconfigureSwitchRecord();
		reconfigureSwitchReplay();
		reconfigureSwitchCompiledReplay();
	}

	public void reconfigureSwitchRecord() {
		keysManager.reRegisterTask(switchRecord, TaskActivation.newBuilder().withHotKey(config.getRECORD()).build());
	}

	public void reconfigureSwitchReplay() {
		keysManager.reRegisterTask(switchReplay, TaskActivation.newBuilder().withHotKey(config.getREPLAY()).build());
	}

	public void reconfigureSwitchCompiledReplay() {
		keysManager.reRegisterTask(switchReplayCompiled, TaskActivation.newBuilder().withHotKey(config.getCOMPILED_REPLAY()).build());
	}

	/*************************************************************************************************************/
	/****************************************Background logging***************************************************/
	public void setEnabledMousePositionLogging(boolean enabled) {
		mousePositionLogger.setEnabled(enabled);
	}

	public boolean isMousePositionLoggingEnabled() {
		return mousePositionLogger.isEnabled();
	}

	public void setEnabledActiveWindowInfosLogging(boolean enabled) {
		activeWindowInfosLogger.setEnabled(enabled);
	}

	public boolean isActiveWindowInfosLoggingEnabled() {
		return activeWindowInfosLogger.isEnabled();
	}

	/*************************************************************************************************************/
	/****************************************Record and replay****************************************************/
	public synchronized void startRecording() {
		if (isRecording) {
			return;
		}
		switchRecord();
	}

	public synchronized void stopRecording() {
		if (!isRecording) {
			return;
		}
		switchRecord();
	}

	protected synchronized void switchRecord() {
		if (isReplaying) { // Do not switch record when replaying.
			return;
		}

		if (!isRecording) { // Start record
			recorder.clear();
			recorder.record();
			isRecording = true;
		} else { // Stop record
			recorder.stopRecord();
			isRecording = false;
		}
	}

	public void setReplayCount(long count) {
		replayConfig = ReplayConfig.of(count, replayConfig.getDelay(), replayConfig.getSpeedup());
	}

	public void setReplayDelay(long delay) {
		replayConfig = ReplayConfig.of(replayConfig.getCount(), delay, replayConfig.getSpeedup());
	}

	public void setReplaySpeedup(float speedup) {
		replayConfig = ReplayConfig.of(replayConfig.getCount(), replayConfig.getDelay(), speedup);
	}

	public synchronized void startReplay() {
		if (isReplaying) {
			return;
		}
		switchReplay();
	}

	public synchronized void stopReplay() {
		if (!isReplaying) {
			return;
		}
		switchReplay();
	}

	protected void switchReplay() {
		if (isRecording) { // Cannot switch replay when recording.
			return;
		}

		if (isReplaying) {
			isReplaying = false;
			recorder.stopReplay();
		} else {
			if (!applySpeedup()) {
				return;
			}

			isReplaying = true;
			recorder.replay(replayConfig.getCount(), replayConfig.getDelay(), new Function<Void, Void>() {
				@Override
				public Void apply(Void r) {
					switchReplay();
					return null;
				}
			}, 5, false);
		}
	}

	public synchronized void runCompiledAction() {
		if (isRunningCompiledTask) {
			return;
		}
		switchRunningCompiledAction();
	}

	public synchronized void stopRunningCompiledAction() {
		if (!isRunningCompiledTask) {
			return;
		}
		switchRunningCompiledAction();
	}

	protected synchronized void switchRunningCompiledAction() {
		if (isRunningCompiledTask) {
			isRunningCompiledTask = false;
			if (compiledExecutor != null) {
				if (compiledExecutor != Thread.currentThread()) {
					while (compiledExecutor.isAlive()) {
						compiledExecutor.interrupt();
					}
				}
			}
		} else {
			if (customFunction == null) {
				LOGGER.warning("No compiled action in memory");
				return;
			}

			isRunningCompiledTask = true;

			compiledExecutor = new Thread(new Runnable() {
			    @Override
				public void run() {
			    	try {
						customFunction.action(coreProvider.get());
					} catch (InterruptedException e) { // Stopped prematurely
						return;
					} catch (Exception e) {
						LOGGER.log(Level.WARNING, "Exception caught while executing custom function", e);
					}

					switchRunningCompiledAction();
			    }
			});
			compiledExecutor.start();
		}
	}

	/*************************************************************************************************************/
	/*****************************************Task group related**************************************************/
	protected void renderTaskGroup() {
		for (TaskGroup group : taskGroups) {
			if (!group.isEnabled()) {
				continue;
			}

			for (UserDefinedAction task : group.getTasks()) {
				Set<UserDefinedAction> collisions = keysManager.isTaskRegistered(task);
				if (task.isEnabled() && (collisions.isEmpty())) {
					keysManager.registerTask(task);
				}
			}
		}
	}

	/**
	 * Add a task group with actions already filled in.
	 * This also registers the task activations for the tasks in group.
	 * Note that this does not replace existing activations with colliding activations.
	 * Task activation registration  continues on failures and only reports one failure at the end.
	 *
	 * @param group group to add.
	 * @return whether operation succeeds (i.e. no activation collision).
	 */
	public boolean addPopulatedTaskGroup(TaskGroup group) {
		boolean result = true;
		taskGroups.add(group);
		for (UserDefinedAction action : group.getTasks()) {
			Set<UserDefinedAction> collisions = keysManager.isActivationRegistered(action.getActivation());
			if (collisions.isEmpty()) {
				keysManager.registerTask(action);
			} else {
				result &= false;
				String collisionNames = StringUtilities.join(collisions.stream().map(t -> t.getName()).collect(Collectors.toList()), ", ");
				LOGGER.log(Level.WARNING, "Cannot register action " + action.getName() + ". There are collisions with " + collisionNames + " in hotkeys!");
			}
		}
		return result;
	}

	public void addTaskGroup(String name) {
		for (TaskGroup group : taskGroups) {
			if (group.getName().equals(name)) {
				LOGGER.warning("This name already exists. Try again.");
				return;
			}
		}

		taskGroups.add(new TaskGroup(name));
	}

	public void removeTaskGroup(String id) {
		int index = getTaskGroupIndex(id);

		if (index < 0 || index >= taskGroups.size()) {
			return;
		}

		TaskGroup removed = taskGroups.remove(index);
		if (taskGroups.size() < 1) {
			taskGroups.add(new TaskGroup("default"));
		}

		if (getCurrentTaskGroup() == removed) {
			setCurrentTaskGroup(taskGroups.get(0));
		}

		for (UserDefinedAction action : removed.getTasks()) {
			keysManager.unregisterTask(action);
		}
		renderTaskGroup();
	}

	public void moveTaskGroupUp(String id) {
		int index = getTaskGroupIndex(id);
		if (index < 1) {
			return;
		}
		Collections.swap(taskGroups, index, index - 1);
	}

	public void moveTaskGroupDown(String id) {
		int index = getTaskGroupIndex(id);

		if (index >= 0 && index < taskGroups.size() - 1) {
			Collections.swap(taskGroups, index, index + 1);
		}
	}

	/*************************************************************************************************************/
	/*****************************************Task related********************************************************/
	public RunActionConfig getRunActionConfig() {
		return runActionConfig;
	}

	public void setRunActionConfig(RunActionConfig config) {
		this.runActionConfig = config;
	}

	private void recompiledNativeTasks(Language language) {
		for (TaskGroup group : taskGroups) {
			List<UserDefinedAction> tasks = group.getTasks();
			for (int i = 0; i < tasks.size(); i++) {
				UserDefinedAction task = tasks.get(i);
				if (task.getCompiler() != language) {
					continue;
				}

				AbstractNativeCompiler compiler = config.getCompilerFactory().getNativeCompiler(task.getCompiler());
				UserDefinedAction recompiled = task.recompileNative(compiler);
				if (recompiled == null) {
					continue;
				}

				tasks.set(i, recompiled);

				if (recompiled.isEnabled()) {
					reRegisterTask(task, recompiled);
				}
			}
		}
	}

	private void recompileRemoteTasks() {
		for (TaskGroup group : taskGroups) {
			List<UserDefinedAction> tasks = group.getTasks();
			for (int i = 0; i < tasks.size(); i++) {
				UserDefinedAction task = tasks.get(i);
				RemoteRepeatsCompiler compiler = config.getCompilerFactory().getRemoteRepeatsCompiler(peerServiceClientManager);
				UserDefinedAction recompiled = task.recompileRemote(compiler);
				if (recompiled == null) {
					continue;
				}

				tasks.set(i, recompiled);

				if (!recompiled.isEnabled()) {
					continue;
				}
				if (group.isEnabled()) {
					reRegisterTask(task, recompiled);
				}
			}
		}
	}

	private void reRegisterTask(UserDefinedAction original, UserDefinedAction action) {
		Set<UserDefinedAction> collisions = keysManager.isTaskRegistered(action);
		boolean conflict = false;
		if (!collisions.isEmpty()) {
			conflict = collisions.size() != 1 || !collisions.iterator().next().equals(original);
		}

		if (!conflict) {
			keysManager.registerTask(action);
		} else {
			List<String> collisionNames = collisions.stream().map(t -> t.getName()).collect(Collectors.toList());
			LOGGER.warning("Unable to register task " + action.getName() + ". Collisions are " + collisionNames);
		}
	}


	/**
	 * Edit source code using the default program to open the source code file (with appropriate extension
	 * depending on the currently selected language).
	 *
	 * This does not update the source code in the text area in the main GUI.
	 */
	public void editSourceCode(String source) {
		AbstractNativeCompiler currentCompiler = getCompiler();
		// Create a temporary file
		try {
			tempSourceFile = File.createTempFile("source", currentCompiler.getExtension());
			tempSourceFile.deleteOnExit();

			FileUtility.writeToFile(source, tempSourceFile, false);
		} catch (IOException e) {
			LOGGER.warning("Encountered error creating temporary source file.\n" + e.getMessage());
			return;
		}

		if (!Desktop.openFile(tempSourceFile)) {
			LOGGER.warning("Unable to open file for editting.\n");
		}
	}

	/**
	 * Load the source code from the temporary source code file into the text area (if the source code file exists).
	 */
	public String reloadSourceCode() {
		if (tempSourceFile == null || !tempSourceFile.exists()) {
			LOGGER.warning("Temp file not accessible.");
			return null;
		}

		StringBuffer sourceCode = FileUtility.readFromFile(tempSourceFile);
		if (sourceCode == null) {
			LOGGER.warning("Unable to read from temp file.");
			return null;
		}
		String source = sourceCode.toString();
		return source;
	}

	private void unregisterTask(UserDefinedAction task) {
		keysManager.unregisterTask(task);
	}

	public void addCurrentTask() {
		addCurrentTask(currentGroup);
	}

	public void addCurrentTask(TaskGroup group) {
		if (customFunction != null) {
			addTask(customFunction, group);
			customFunction = null;
		} else {
			LOGGER.warning("Nothing to add. Compile first?");
		}
	}

	public void addTask(UserDefinedAction task, TaskGroup group) {
		if (task.getName() == null || task.getName().isEmpty()) {
			task.setName("New task");
		}
		group.getTasks().add(task);

		writeConfigFile();
		cleanUnusedSource();
	}

	/**
	 * Add task to a special remote task group.
	 * If this group does not exist yet, create it.
	 */
	public void addRemoteCompiledTask(UserDefinedAction task) {
		for (TaskGroup group : taskGroups) {
			if (group.getGroupId().equals(TaskGroup.REMOTE_TASK_GROUP_ID)) {
				addTask(task, group);
				return;
			}
		}

		TaskGroup remoteGroup = TaskGroup.remoteTaskGroup();
		taskGroups.add(remoteGroup);
		addTask(task, remoteGroup);
	}

	public UserDefinedAction getTask(String id) {
		for (TaskGroup group : taskGroups) {
			UserDefinedAction task = group.getTask(id);
			if (task != null) {
				return task;
			}
		}
		return null;
	}

	/**
	 * Get first task with given name in the task group.
	 * Returning null if no task with given name exists.
	 */
	public UserDefinedAction getTaskByName(String name) {
		for (TaskGroup group : taskGroups) {
			UserDefinedAction task = group.getTaskByName(name);
			if (task != null) {
				return task;
			}
		}
		return null;
	}

	public void removeCurrentTask(String id) {
		boolean found = false;
		for (ListIterator<UserDefinedAction> iterator = currentGroup.getTasks().listIterator(); iterator.hasNext();) {
			UserDefinedAction action = iterator.next();
			if (!action.getActionId().equals(id)) {
				continue;
			}
			found = true;
			unregisterTask(action);
			iterator.remove();
			break;
		}
		if (!found) {
			LOGGER.info("Select a row from the table to remove.");
			return;
		}

		writeConfigFile();
	}

	public void removeTask(String id) {
		UserDefinedAction toRemove = getTask(id);
		if (toRemove == null) {
			return;
		}

		removeTask(toRemove);
	}

	public void removeTask(UserDefinedAction toRemove) {
		for (TaskGroup group : taskGroups) {
			for (Iterator<UserDefinedAction> iterator = group.getTasks().iterator(); iterator.hasNext();) {
				UserDefinedAction action = iterator.next();
				if (action != toRemove) {
					continue;
				}
				unregisterTask(action);

				iterator.remove();

				writeConfigFile();
				return;
			}
		}
	}

	public void moveTaskUp(String taskId) {
		int selected = getTaskIndex(taskId, currentGroup);
		if (selected < 1) {
			return;
		}
		Collections.swap(currentGroup.getTasks(), selected, selected - 1);
	}

	public void moveTaskDown(String taskId) {
		int selected = getTaskIndex(taskId, currentGroup);
		if (selected >= 0 && selected < currentGroup.getTasks().size() - 1) {
			Collections.swap(currentGroup.getTasks(), selected, selected + 1);
		}
	}

	private int getTaskIndex(String taskId, TaskGroup group) {
		for (ListIterator<UserDefinedAction> iterator = group.getTasks().listIterator(); iterator.hasNext();) {
			int index = iterator.nextIndex();
			UserDefinedAction action = iterator.next();
			if (action.getActionId().equals(taskId)) {
				return index;
			}
		}
		return -1;
	}

	public void changeTaskGroup(String taskId, String newGroupId) {
		int newGroupIndex = getTaskGroupIndex(newGroupId);
		if (newGroupIndex == -1) {
			LOGGER.warning("Cannot change task group to group with ID " + newGroupId + " since it does not exist.");
			return;
		}

		TaskGroup destination = taskGroups.get(newGroupIndex);
		if (destination == currentGroup) {
			LOGGER.warning("Cannot move to the same group.");
			return;
		}

		if (currentGroup.isEnabled() ^ destination.isEnabled()) {
			LOGGER.warning("Two groups must be both enabled or disabled to move...");
			return;
		}

		UserDefinedAction toMove = null;
		for (Iterator<UserDefinedAction> iterator = currentGroup.getTasks().iterator(); iterator.hasNext();) {
			toMove = iterator.next();
			if (toMove.getActionId().equals(taskId)) {
				iterator.remove();
				break;
			}
		}
		if (toMove == null) {
			return;
		}

		destination.getTasks().add(toMove);
		writeConfigFile();
	}

	public void overwriteTask(String taskId) {
		if (customFunction == null) {
			LOGGER.info("Nothing to override. Compile first?");
			return;
		}

		for (ListIterator<UserDefinedAction> iterator = currentGroup.getTasks().listIterator(); iterator.hasNext();) {
			UserDefinedAction action = iterator.next();
			if (!action.getActionId().equals(taskId)) {
				continue;
			}
			customFunction.override(action);

			unregisterTask(action);
			keysManager.registerTask(customFunction);
			iterator.set(customFunction);

			LOGGER.info("Successfully overridden task " + customFunction.getName());
			customFunction = null;
			if (!writeConfigFile()) {
				LOGGER.warning("Unable to update config.");
			}
			break;
		}
		cleanUnusedSource();
	}

	public boolean changeHotkeyTask(UserDefinedAction action, TaskActivation newActivation) {
		if (newActivation == null) {
			return false;
		}

		Set<UserDefinedAction> collisions = keysManager.isActivationRegistered(newActivation);
		collisions.remove(action);
		if (!collisions.isEmpty()) {
			GlobalEventsManager.showCollisionWarning(null, collisions);
			return false;
		}

		keysManager.reRegisterTask(action, newActivation);
		return true;
	}

	public void switchEnableTask(UserDefinedAction action) {
		if (action.isEnabled()) { // Then disable it
			action.setEnabled(false);
			if (!action.isEnabled()) {
				keysManager.unregisterTask(action);
			}
		} else { // Then enable it
			Set<UserDefinedAction> collisions = keysManager.isTaskRegistered(action);
			if (!collisions.isEmpty()) {
				GlobalEventsManager.showCollisionWarning(null, collisions);
				return;
			}

			action.setEnabled(true);
			if (!action.isEnabled()) {
				return;
			}

			// Check if the group containing the action is enabled.
			for (TaskGroup group : taskGroups) {
				if (group.getTask(action.getActionId()) == null) {
					continue;
				}
				// Found the group. Check if the group is enabled.
				if (!group.isEnabled()) {
					LOGGER.info("Task " + action.getName() + " is enabled but not registered because the group containing it is not enabled.");
					return;
				}
			}

			keysManager.registerTask(action);
		}
	}

	public String getSourceForTask(UserDefinedAction action, long timestamp) {
		TaskSourceHistoryEntry entry = action.getTaskSourceHistory().findEntry(timestamp);
		if (entry == null) {
			LOGGER.warning("No source path for action " + action.getName() + " at time " + timestamp + ".");
			return null;
		}

		StringBuffer content = FileUtility.readFromFile(entry.getSourcePath());
		if (content == null) {
			LOGGER.warning("No source content for action " + action.getName() + " at file " + entry.getSourcePath() + ".");
			return null;
		}
		return content.toString();
	}

	/**
	 * Populate all tasks with task invoker to dynamically execute other tasks.
	 */
	private void setTaskInvoker() {
		for (TaskGroup taskGroup : taskGroups) {
			for (UserDefinedAction task : taskGroup.getTasks()) {
				task.setTaskInvoker(taskInvoker);
			}
		}
	}

	/*************************************************************************************************************/
	/********************************************Source code related**********************************************/

	public String generateSource() {
		String source = "";
		if (applySpeedup()) {
			source = recorder.getGeneratedCode(getSelectedLanguage());
		}
		return source;
	}

	public void importTasks(File inputFile) {
		ZipUtility.unZipFile(inputFile.getAbsolutePath(), ".");
		File src = new File("tmp");
		File dst = new File(".");
		boolean moved = FileUtility.moveDirectory(src, dst);
		if (!moved) {
			LOGGER.warning("Failed to move files from " + src.getAbsolutePath() + " to " + dst.getAbsolutePath());
			return;
		}
		int existingGroupCount = taskGroups.size();
		boolean result = config.importTaskConfig();
		FileUtility.deleteFile(new File("tmp"));
		FileUtility.deleteFile(new File(Config.EXPORTED_CONFIG_FILE_NAME));

		if (taskGroups.size() > existingGroupCount) {
			currentGroup = taskGroups.get(existingGroupCount); // Take the new group with lowest index.
			setTaskInvoker();
		} else {
			LOGGER.warning("No new task group found!");
			return;
		}
		if (result) {
			LOGGER.info("Successfully imported tasks. Switching to a new task group...");
		} else {
			LOGGER.warning("Encountered error(s) while importing tasks. Switching to a new task group...");
		}
	}

	public void exportTasks(File outputDirectory) {
		File destination = new File(FileUtility.joinPath(outputDirectory.getAbsolutePath(), "tmp"));
		String zipPath = FileUtility.joinPath(outputDirectory.getAbsolutePath(), "repeat_export.zip");

		FileUtility.createDirectory(destination.getAbsolutePath());
		config.exportTasksConfig(destination);
		// Now create a zip file containing all source codes together with the config file
		for (TaskGroup group : taskGroups) {
			for (UserDefinedAction task : group.getTasks()) {
				File sourceFile = new File(task.getSourcePath());
				String destPath = FileUtility.joinPath(destination.getAbsolutePath(), FileUtility.getRelativePwdPath(sourceFile));
				File destFile = new File(destPath);
				FileUtility.copyFile(sourceFile, destFile);
			}
		}

		File zipFile = new File(zipPath);
		ZipUtility.zipDir(destination, zipFile);
		FileUtility.deleteFile(destination);

		LOGGER.info("Data exported to " + zipPath);
	}

	public void cleanUnusedSource() {
		List<File> files = FileUtility.walk(FileUtility.joinPath("data", "source"));
		Set<String> allNames = new HashSet<>(new Function<File, String>() {
			@Override
			public String apply(File file) {
				return file.getAbsolutePath();
			}
		}.map(files));

		Set<String> using = new HashSet<>();
		for (TaskGroup group : taskGroups) {
			for (UserDefinedAction task : group.getTasks()) {
				List<String> sources = new ArrayList<>();
				String currentSource = new File(task.getSourcePath()).getAbsolutePath();
				sources.add(currentSource);
				sources.addAll(task.getTaskSourceHistory().getEntries().stream().map(e -> new File(e.getSourcePath()).getAbsolutePath()).collect(Collectors.toList()));
				using.addAll(sources);
			}
		}

		allNames.removeAll(using);
		if (allNames.size() == 0) {
			LOGGER.info("Nothing to clean...");
			return;
		}

		int count = 0, failed = 0;
		for (String name : allNames) {
			if (FileUtility.removeFile(new File(name))) {
				count++;
			} else {
				failed++;
			}
		}

		LOGGER.info("Successfully cleaned " + count + " files.\n Failed to clean " + failed + " files.");
	}

	/*************************************************************************************************************/
	/***************************************Source compilation****************************************************/

	public Language getSelectedLanguage() {
		return compilingLanguage;
	}

	public AbstractNativeCompiler getCompiler() {
		return config.getCompilerFactory().getNativeCompiler(getSelectedLanguage());
	}

	public void setCompilingLanguage(Language language) {
		compilingLanguage = language;

		customFunction = null;
	}

	/**
	 * Compile the given source code and sets it as the currently compiled action.
	 *
	 * @param source source code to compile.
	 * @param taskName name of the newly created task. A default name will be given if not provided.
	 */
	public boolean compileSourceAndSetCurrent(String source, String taskName) {
		AbstractNativeCompiler compiler = getCompiler();
		UserDefinedAction createdInstance = compileSourceNatively(compiler, source, taskName);
		if (createdInstance == null) {
			return false;
		}

		if (config.getCompilerFactory().getRemoteRepeatsCompilerConfig().hasOnlyLocal()) {
			customFunction = createdInstance;
			return true;
		}

		RemoteRepeatsCompiler remoteRepeatsCompiler = config.getCompilerFactory().getRemoteRepeatsCompiler(peerServiceClientManager);
		RemoteRepeatsDyanmicCompilationResult remoteCompilationResult = remoteRepeatsCompiler.compile(source, getSelectedLanguage());
		if (remoteCompilationResult.output() != DynamicCompilerOutput.COMPILATION_SUCCESS) {
			return false;
		}

		customFunction = CompositeUserDefinedAction.of(
				createdInstance,
				config.getCompilerFactory().getRemoteRepeatsCompilerConfig(),
				remoteCompilationResult.clientIdToActionId(),
				remoteCompilationResult.action());
		return true;
	}

	public UserDefinedAction compileSourceNatively(AbstractNativeCompiler compiler, String source, String taskName) {
		source = source.replaceAll("\t", "    "); // Use spaces instead of tabs

		DynamicCompilationResult compilationResult = compiler.compile(source);
		DynamicCompilerOutput compilerStatus = compilationResult.output();
		UserDefinedAction createdInstance = compilationResult.action();
		if (taskName != null && !taskName.isEmpty()) {
			createdInstance.setName(taskName);
		}

		if (compilerStatus != DynamicCompilerOutput.COMPILATION_SUCCESS) {
			return null;
		}

		createdInstance.setTaskInvoker(taskInvoker);
		createdInstance.setCompiler(compiler.getName());

		if (!TaskSourceManager.submitTask(createdInstance, source)) {
			LOGGER.warning("Error writing source file.");
			return null;
		}
		return createdInstance;
	}

	/*************************************************************************************************************/
	/***************************************Configurations********************************************************/
	// Write configuration file
	public boolean writeConfigFile() {
		boolean result = config.writeConfig();
		if (!result) {
			LOGGER.warning("Unable to update config.");
		}

		return result;
	}

	public void changeDebugLevel(Level level) {
		config.setNativeHookDebugLevel(level);

		Logger.getLogger("").setLevel(level);
		for (Handler h : Logger.getLogger("").getHandlers()) {
			h.setLevel(level);
		}
	}

	public void haltAllTasks() {
		actionExecutor.haltAllTasks();
	}

	/**
	 * Apply the current speedup in the textbox.
	 * This attempts to parse the speedup.
	 *
	 * @return if the speedup was successfully parsed and applied.
	 */
	private boolean applySpeedup() {
		recorder.setSpeedup(replayConfig.getSpeedup());
		return true;
	}

	public void setToolsClients(List<String> clients) {
		config.getToolsConfig().setClients(clients);
		List<ITools> tools = clients.stream().map(c -> {
			if (c.equals(AbstractRemoteRepeatsClientsConfig.LOCAL_CLIENT)) {
				return Tools.local();
			}
			return new RemoteRepeatsClientTools(peerServiceClientManager, c);
		}).collect(Collectors.toList());
		DefaultTools.setExecutor(AggregateTools.of(tools));
	}

	public void setCoreClients(List<String> clients) {
		config.getCoreConfig().setClients(clients);
	}

	/*************************************************************************************************************/
	/***************************************User Interface********************************************************/
	protected void launchUI() {
		int port = IPCServiceManager.getIPCService(IPCServiceName.WEB_UI_SERVER).getPort();
		String url = "http://localhost:" + port;
		String mainMessage = "Initialization finished. UI server is at " + url;

		String logMessage = "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n" +
						mainMessage + "\n" +
						"@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@";
		LOGGER.info(logMessage);
	}

	/*************************************************************************************************************/
	/***************************************Generic Getters and Setters*******************************************/
	public Recorder getRecorder() {
		return recorder;
	}

	public Config getConfig() {
		return config;
	}

	public CoreProvider getCoreProvider() {
		return coreProvider;
	}

	public synchronized boolean isRecording() {
		return isRecording;
	}

	public ReplayConfig getReplayConfig() {
		return replayConfig;
	}

	public synchronized boolean isReplaying() {
		return isReplaying;
	}

	public synchronized boolean isRunningCompiledAction() {
		return isRunningCompiledTask;
	}

	public ActionExecutor getActionExecutor() {
		return actionExecutor;
	}

	public GlobalEventsManager getKeysManager() {
		return keysManager;
	}

	public RepeatsPeerServiceClientManager getPeerServiceClientManager() {
		return peerServiceClientManager;
	}

	public String getLogsSince(long time) {
		return logHolder.getContentSince(time);
	}

	public void clearLogs() {
		logHolder.clear();
	}

	public void addTaskGroup(TaskGroup group) {
		taskGroups.add(group);
	}

	public void clearTaskGroup() {
		taskGroups.clear();
	}

	/**
	 * Get the task group with the index, returning null if index is out of range.
	 */
	public TaskGroup getTaskGroup(int index) {
		if (index < 0 || index >= taskGroups.size()) {
			return null;
		}
		return taskGroups.get(index);
	}

	private int getTaskGroupIndex(String id) {
		for (ListIterator<TaskGroup> iterator = taskGroups.listIterator(); iterator.hasNext();) {
			int index = iterator.nextIndex();
			TaskGroup group = iterator.next();
			if (group.getGroupId().equals(id)) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Get the task group with the given id, or null if no such group exists.
	 */
	public TaskGroup getTaskGroup(String id) {
		for (TaskGroup group : taskGroups) {
			if (group.getGroupId().equals(id)) {
				return group;
			}
		}
		return null;
	}

	/**
	 * Get the first task group with the given name, or null if no such group
	 * exists.
	 */
	public TaskGroup getTaskGroupFromName(String name) {
		for (TaskGroup group : taskGroups) {
			if (group.getName().equals(name)) {
				return group;
			}
		}
		return null;
	}

	/** Retrieve an immutable view of the list of task groups. */
	public List<TaskGroup> getTaskGroups() {
		return Collections.unmodifiableList(taskGroups);
	}

	public int getCurentTaskGroupIndex() {
		TaskGroup current = getCurrentTaskGroup();
		return getTaskGroupIndex(current.getGroupId());
	}

	public TaskGroup getCurrentTaskGroup() {
		return this.currentGroup;
	}

	public void setCurrentTaskGroup(TaskGroup currentTaskGroup) {
		if (currentTaskGroup != this.currentGroup) {
			this.currentGroup = currentTaskGroup;
			keysManager.setCurrentTaskGroup(currentTaskGroup);
		}
	}
}
