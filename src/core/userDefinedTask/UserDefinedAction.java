package core.userDefinedTask;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

import utilities.ExceptableFunction;
import utilities.FileUtility;
import argo.jdom.JsonNode;
import argo.jdom.JsonNodeFactories;
import argo.jdom.JsonRootNode;
import core.config.IJsonable;
import core.controller.Core;
import core.keyChain.KeyChain;
import core.languageHandler.compiler.DynamicCompiler;
import core.languageHandler.compiler.DynamicCompilerFactory;

public abstract class UserDefinedAction implements IJsonable {

	protected String name;
	protected Set<KeyChain> hotkeys;
	protected String sourcePath;
	protected String compilerName;
	protected boolean enabled;
	protected ExceptableFunction<Integer, Void, InterruptedException> executeTaskInGroup;
	protected KeyChain invokingKeyChain;

	public UserDefinedAction() {
		invokingKeyChain = new KeyChain();
		enabled = true;
	}

	/**
	 * Custom action defined by user
	 * @param controller See {@link core.controller.Core} class
	 * @throws InterruptedException
	 */
	public abstract void action(Core controller) throws InterruptedException;

	public void setName(String name) {
		this.name = name;
	}

	public void setHotKeys(Set<KeyChain> hotkeys) {
		this.hotkeys = hotkeys;
	}

	public Set<KeyChain> getHotkeys() {
		if (hotkeys == null) {
			hotkeys = new HashSet<KeyChain>();
		}
		return hotkeys;
	}

	/**
	 * Retrieve a random key chain from the set of key chains. If there's no keychain for the task, return an empty key chain.
	 * @return a random key chain from the set of key chains.
	 */
	public KeyChain getRepresentativeHotkey() {
		Set<KeyChain> hotkeys = getHotkeys();
		if (hotkeys == null || hotkeys.isEmpty()) {
			return new KeyChain();
		} else {
			return hotkeys.iterator().next();
		}
	}

	public String getName() {
		return name;
	}

	public String getSourcePath() {
		return sourcePath;
	}

	public void setSourcePath(String sourcePath) {
		this.sourcePath = sourcePath;
	}

	public String getCompiler() {
		return compilerName;
	}

	public void setCompiler(String compiler) {
		this.compilerName = compiler;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * This method is called to dynamically allow the current task to execute other tasks in group
	 * @param executeTaskInGroup
	 */
	public void setExecuteTaskInGroup(ExceptableFunction<Integer, Void, InterruptedException> executeTaskInGroup) {
		this.executeTaskInGroup = executeTaskInGroup;
	}

	/**
	 * This method is called to dynamically allow the current task to determine which key chain activated it among
	 * its hotkeys. This will only change the key chain definition of the current key chain, not substituting the real object
	 * @param invokingKeyChain
	 */
	public void setInvokingKeyChain(KeyChain invokingKeyChain) {
		this.invokingKeyChain.getKeys().clear();
		this.invokingKeyChain.getKeys().addAll(invokingKeyChain.getKeys());
	}

	/***********************************************************************/
	@Override
	public final JsonRootNode jsonize() {
		List<JsonNode> hotkeysJSON = new LinkedList<>();
		for (KeyChain hotkey : getHotkeys()) {
			hotkeysJSON.add(hotkey.jsonize());
		}

		return JsonNodeFactories.object(
				JsonNodeFactories.field("source_path", JsonNodeFactories.string(sourcePath)),
				JsonNodeFactories.field("compiler", JsonNodeFactories.string(compilerName)),
				JsonNodeFactories.field("name", JsonNodeFactories.string(name)),
				JsonNodeFactories.field("hotkey", JsonNodeFactories.array(hotkeysJSON)),
				JsonNodeFactories.field("enabled", JsonNodeFactories.booleanNode(enabled))
				);

	}

	public static UserDefinedAction parseJSON(DynamicCompilerFactory factory, JsonNode node) {
		try {
			String sourcePath = node.getStringValue("source_path");
			DynamicCompiler compiler = factory.getCompiler(node.getStringValue("compiler"));
			if (compiler == null) {
				JOptionPane.showMessageDialog(null, "Unknown compiler " + node.getStringValue("compiler"));
				return null;
			}

			String name = node.getStringValue("name");
			List<JsonNode> hotkeyJSONs =  node.getArrayNode("hotkey");
			Set<KeyChain> hotkeys = new HashSet<>();
			for (JsonNode hotkeyJSON : hotkeyJSONs) {
				hotkeys.add(KeyChain.parseJSON(hotkeyJSON.getArrayNode()));
			}

			File sourceFile = new File(sourcePath);
			StringBuffer sourceBuffer = FileUtility.readFromFile(sourceFile);
			String source = null;
			if (sourceBuffer == null) {
				JOptionPane.showMessageDialog(null, "Cannot get source at path " + sourcePath);
				return null;
			} else {
				source = sourceBuffer.toString();
			}

			File objectFile = new File(FileUtility.joinPath("core", FileUtility.removeExtension(sourceFile).getName()));
			objectFile = FileUtility.addExtension(objectFile, compiler.getObjectExtension());
			UserDefinedAction output = compiler.compile(source, objectFile);
			if (output == null) {
				JOptionPane.showMessageDialog(null, "Compilation failed for task " + name + " with source at path " + sourcePath);
				return null;
			}

			boolean enabled = node.getBooleanValue("enabled");

			output.sourcePath = sourcePath;
			output.compilerName = compiler.getName();
			output.name = name;
			output.hotkeys = hotkeys;
			output.enabled = enabled;

			return output;
		} catch (Exception e) {
			Logger.getLogger(UserDefinedAction.class.getName()).log(Level.WARNING, "Exception parsing task from JSON", e);
			return null;
		}
	}

}