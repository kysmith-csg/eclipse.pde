package org.eclipse.pde.internal.build;
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.model.PluginModel;

/**
 * Generates build.xml script for features.
 */
public class PluginBuildScriptGenerator extends ModelBuildScriptGenerator {

protected PluginModel getModel(String modelId) throws CoreException {
	return getRegistry().getPlugin(modelId);
}

protected String getModelTypeName() {
	return "plugin";
}

protected String getDirectoryName() {
	return "plugins/${plugin}_${version}";
}
}
