/*******************************************************************************
 * Copyright (c) 2005, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.views.plugins;

import java.util.ArrayList;
import java.util.Iterator;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.pde.core.plugin.IPluginBase;
import org.eclipse.pde.core.plugin.IPluginExtension;
import org.eclipse.pde.core.plugin.IPluginExtensionPoint;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.pde.internal.ui.wizards.imports.PluginImportOperation;
import org.eclipse.pde.internal.ui.wizards.imports.PluginImportWizard;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.actions.ActionContext;
import org.eclipse.ui.actions.ActionGroup;

public class ImportActionGroup extends ActionGroup {

	class ImportAction extends Action {
		IStructuredSelection fSel;
		int fImportType;
		ImportAction(int importType, IStructuredSelection selection) {
			fSel = selection;
			fImportType = importType;
			switch (fImportType) {
			case PluginImportOperation.IMPORT_BINARY:
				setText(PDEUIMessages.PluginsView_asBinaryProject);
				break;
			case PluginImportOperation.IMPORT_BINARY_WITH_LINKS:
				setText(PDEUIMessages.ImportActionGroup_binaryWithLinkedContent);
				break;
			case PluginImportOperation.IMPORT_WITH_SOURCE:
				setText(PDEUIMessages.PluginsView_asSourceProject);
				break;
			}
		}
		public void run() {
			handleImport(fImportType, fSel);
		}
	}

	public void fillContextMenu(IMenuManager menu) {
		ActionContext context = getContext();
		ISelection selection = context.getSelection();
		if (!selection.isEmpty() && selection instanceof IStructuredSelection) {
			IStructuredSelection sSelection = (IStructuredSelection) selection;
			String menuName = null;
			if (sSelection.getFirstElement() instanceof IPluginExtension || 
					sSelection.getFirstElement() instanceof IPluginExtensionPoint)
				menuName = PDEUIMessages.ImportActionGroup_importContributingPlugin;
			else
				menuName = PDEUIMessages.PluginsView_import;
			MenuManager importMenu = new MenuManager(menuName); 
			importMenu.add(new ImportAction(PluginImportOperation.IMPORT_BINARY, sSelection));
			importMenu.add(new ImportAction(PluginImportOperation.IMPORT_BINARY_WITH_LINKS, sSelection));
			importMenu.add(new ImportAction(PluginImportOperation.IMPORT_WITH_SOURCE, sSelection));
			menu.add(importMenu);
		}
	}

	private void handleImport(int importType, IStructuredSelection selection) {
		ArrayList externalModels = new ArrayList();
		for (Iterator iter = selection.iterator(); iter.hasNext();) {
			IPluginModelBase model = getModel(iter.next());
			if (model != null && model.getUnderlyingResource() == null)
				externalModels.add(model);
		}
		Display display = Display.getCurrent();
		if (display == null)
			display = Display.getDefault();
		IPluginModelBase[] models =
			(IPluginModelBase[]) externalModels.toArray(
					new IPluginModelBase[externalModels.size()]);

		PluginImportWizard.doImportOperation(display.getActiveShell(), importType, models, false);
	}

	public static boolean canImport(IStructuredSelection selection) {
		for (Iterator iter = selection.iterator(); iter.hasNext();) {
			IPluginModelBase model = getModel(iter.next());
			if (model != null && model.getUnderlyingResource() == null)
				return true;
		}
		return false;
	}
	
	private static IPluginModelBase getModel(Object next) {
		IPluginModelBase model = null;
		if (next instanceof IPluginModelBase)
			model = (IPluginModelBase) next;
		else if (next instanceof IPluginBase)
			model = ((IPluginBase)next).getPluginModel();
		else if (next instanceof IPluginExtension)
			model = ((IPluginExtension)next).getPluginModel();
		else if (next instanceof IPluginExtensionPoint)
			model = ((IPluginExtensionPoint)next).getPluginModel();
		return model;
	}
}
