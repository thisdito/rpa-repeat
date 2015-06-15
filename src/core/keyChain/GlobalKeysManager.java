package core.keyChain;

import globalListener.GlobalKeyListener;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;

import utilities.CodeConverter;
import utilities.ExceptableFunction;
import utilities.Function;
import utilities.RandomUtil;
import core.config.Config;
import core.config.Parser1_0;
import core.controller.Core;
import core.userDefinedTask.TaskGroup;
import core.userDefinedTask.UserDefinedAction;

public final class GlobalKeysManager {

	private static final Logger LOGGER = Logger.getLogger(Parser1_0.class.getName());

	private final Core controller;
	private final Map<KeyChain, UserDefinedAction> actionMap = new HashMap<>();
	private Function<Void, Boolean> disablingFunction = Function.falseFunction();
	private final Map<String, Thread> executions;
	private final KeyChain currentKeyChain;

	private TaskGroup currentTaskGroup;

	public GlobalKeysManager(Config config, Core controller) {
		this.controller = controller;
		this.executions = new HashMap<>();
		this.currentKeyChain = new KeyChain();
	}

	public void startGlobalListener() throws NativeHookException {
		GlobalKeyListener keyListener = new GlobalKeyListener();
		keyListener.setKeyPressed(new Function<NativeKeyEvent, Boolean>() {
			@Override
			public Boolean apply(NativeKeyEvent r) {
				int code = CodeConverter.getKeyEventCode(r.getKeyCode());

				if (code == Config.HALT_TASK) {
					LinkedList<Thread> endings = new LinkedList<>();
					endings.addAll(executions.values());

					for (Thread t : endings) {
						while (t.isAlive() && t != Thread.currentThread()) {
							LOGGER.info("Interrupting execution thread " + t);
							t.interrupt();
						}
					}
					executions.clear();

					return true;
				}

				if (disablingFunction.apply(null)) {
					return true;
				}

				currentKeyChain.getKeys().add(code);

				final UserDefinedAction action = actionMap.get(currentKeyChain);
				if (action != null) {
					final String id = RandomUtil.randomID();
					Thread execution = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								action.setInvokingKeyChain(currentKeyChain);
								action.setExecuteTaskInGroup(new ExceptableFunction<Integer, Void, InterruptedException> () {
									@Override
									public Void apply(Integer d) throws InterruptedException {
										if (currentTaskGroup == null) {
											LOGGER.warning("Task group is null. Cannot execute given task with index " + d);
											return null;
										}
										List<UserDefinedAction> tasks = currentTaskGroup.getTasks();

										if (d >= 0 && d < tasks.size()) {
											currentTaskGroup.getTasks().get(d).action(controller);
										} else {
											LOGGER.warning("Index out of bound. Cannot execute given task with index " + d + " given task group only has " + tasks.size() + " elements.");
										}

										return null;
									}
								});
								action.action(controller);
							} catch (InterruptedException e) {
								LOGGER.info("Task ended prematurely");
							} catch (Exception e) {
								String name = action.getName() == null ? "" : action.getName();
								LOGGER.log(Level.WARNING, "Exception while executing task " + name, e);
							}

							executions.remove(id);
						}
					});

					executions.put(id, execution);
					execution.start();

					return true;
				}

				return true;
			}
		});

		keyListener.setKeyReleased(new Function<NativeKeyEvent, Boolean>() {
			@Override
			public Boolean apply(NativeKeyEvent r) {
				int code = CodeConverter.getKeyEventCode(r.getKeyCode());

				if (code == Config.HALT_TASK) {
					currentKeyChain.getKeys().clear();
					return true;
				}

				currentKeyChain.getKeys().clear();
				return true;
			}
		});
		keyListener.startListening();
	}

	public void setDisablingFunction(Function<Void, Boolean> disablingFunction) {
		this.disablingFunction = disablingFunction;
	}

	public void setCurrentTaskGroup(TaskGroup currentTaskGroup) {
		this.currentTaskGroup = currentTaskGroup;
	}

	/**
	 * Map all key chains of the current task to the action. Kick out all colliding tasks
	 * @param action
	 * @return List of currently registered tasks that collide with this newly registered task
	 */
	public Set<UserDefinedAction> registerTask(UserDefinedAction action) {
		Set<KeyChain> collisions = areKeysRegistered(action.getHotkeys());
		Set<UserDefinedAction> output = new HashSet<>();

		for (KeyChain key : collisions) {
			UserDefinedAction toRemove = actionMap.get(key);
			if (toRemove != null) {
				output.add(toRemove);
			}
		}

		for (KeyChain key : action.getHotkeys()) {
			registerKey(key, action);
		}

		return output;
	}

	public Set<UserDefinedAction> reRegisterTask(UserDefinedAction action, Collection<KeyChain> newKeyChains) {
		unregisterTask(action);
		action.getHotkeys().clear();
		action.getHotkeys().addAll(newKeyChains);
		return registerTask(action);
	}

	/**
	 * Remove all bindings to the task's keyChains
	 * @param action
	 * @return if all keys are removed
	 */
	public boolean unregisterTask(UserDefinedAction action) {
		for (KeyChain k : action.getHotkeys()) {
			unregisterKey(k);
		}

		return true;
	}

	/**
	 * @param action
	 * @return list of key chains that collide with the key chains of this task
	 */
	public Set<KeyChain> isTaskRegistered(UserDefinedAction action) {
		return areKeysRegistered(action.getHotkeys());
	}

	/**
	 * Check if an iterable of key chain are registered
	 * @param codes
	 * @return return all currently registered key chains that collide with the key chains in iterable
	 */
	public Set<KeyChain> areKeysRegistered(Collection<KeyChain> codes) {
		Set<KeyChain> output = new HashSet<>();
		for (KeyChain code : codes) {
			KeyChain collision = isKeyRegistered(code);
			if (collision != null) {
				output.add(collision);
			}
		}
		return output;
	}

	public KeyChain isKeyRegistered(KeyChain code) {
		for (KeyChain existing : actionMap.keySet()) {
			if (existing.collideWith(code) && !existing.equals(code)) {
				return existing;
			}
		}

		return null;
	}

	public KeyChain isKeyRegistered(int code) {
		return isKeyRegistered(new KeyChain(code));
	}

	private UserDefinedAction unregisterKey(KeyChain code) {
		return actionMap.remove(code);
	}

	private UserDefinedAction registerKey(KeyChain code, UserDefinedAction action) {
		if (code.getKeys().contains(Config.HALT_TASK)) {
			return null;
		}

		UserDefinedAction removal = actionMap.get(code);
		actionMap.put(code, action);

		return removal;
	}
}