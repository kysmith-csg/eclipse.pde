/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Wolfgang Schell <ws@jetztgrad.net> - bug 259348
 *******************************************************************************/
package org.eclipse.pde.internal.runtime.registry;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.pde.internal.runtime.registry.model.*;

public class RegistryBrowserContentProvider implements ITreeContentProvider {

	public boolean isInExtensionSet;
	private boolean isInUsingBundles;

	private RegistryBrowser fRegistryBrowser;

	public RegistryBrowserContentProvider(RegistryBrowser registryBrowser) {
		fRegistryBrowser = registryBrowser;
	}

	public void dispose() { // nothing to dispose
	}

	public Object[] getElements(Object element) {
		return getChildren(element);
	}

	public Object[] getChildren(Object element) {
		if (isInUsingBundles && (!(element instanceof Bundle)))
			isInUsingBundles = false;

		if (element instanceof RegistryModel) {
			RegistryModel model = (RegistryModel) element;

			switch (fRegistryBrowser.getGroupBy()) {
				case (RegistryBrowser.BUNDLES) :
					return model.getBundles();
				case (RegistryBrowser.EXTENSION_REGISTRY) :
					return model.getExtensionPoints();
				case (RegistryBrowser.SERVICES) :
					return model.getServiceNames();
			}

			return null;
		}

		if (element instanceof Extension)
			return ((Extension) element).getConfigurationElements();

		isInExtensionSet = false;
		if (element instanceof ExtensionPoint)
			return ((ExtensionPoint) element).getExtensions().toArray();

		if (element instanceof ConfigurationElement)
			return ((ConfigurationElement) element).getElements();

		if (element instanceof Bundle) {
			if (fRegistryBrowser.getGroupBy() != RegistryBrowser.BUNDLES) // expands only in Bundles mode
				return null;

			if (isInUsingBundles) // avoid infinite nesting: Bundle->Services->UsingBundles->Bundle->Services...
				return null;

			Bundle bundle = (Bundle) element;

			List folders = new ArrayList(7);

			folders.add(new Attribute(Attribute.F_LOCATION, bundle.getLocation()));
			if (bundle.getImports().length > 0)
				folders.add(new Folder(Folder.F_IMPORTS, bundle));
			if (bundle.getLibraries().length > 0)
				folders.add(new Folder(Folder.F_LIBRARIES, bundle));
			if (bundle.getExtensionPoints().length > 0)
				folders.add(new Folder(Folder.F_EXTENSION_POINTS, bundle));
			if (bundle.getExtensions().length > 0)
				folders.add(new Folder(Folder.F_EXTENSIONS, bundle));
			if (bundle.getRegisteredServices().length > 0)
				folders.add(new Folder(Folder.F_REGISTERED_SERVICES, bundle));
			if (bundle.getServicesInUse().length > 0)
				folders.add(new Folder(Folder.F_SERVICES_IN_USE, bundle));

			return folders.toArray();
		}

		isInExtensionSet = false;

		if (element instanceof Folder) {
			Folder folder = (Folder) element;
			isInExtensionSet = folder.getId() == Folder.F_EXTENSIONS;
			Object[] objs = ((Folder) element).getChildren();
			isInUsingBundles = folder.getId() == Folder.F_USING_BUNDLES;
			return objs;
		}
		if (element instanceof ConfigurationElement) {
			return ((ConfigurationElement) element).getElements();
		}

		if (element instanceof ExtensionPoint) {
			ExtensionPoint extensionPoint = (ExtensionPoint) element;
			Object[] objs = extensionPoint.getExtensions().toArray();
			return objs;
		}

		if (element instanceof ServiceName) {
			return ((ServiceName) element).getChildren();
		}

		if (element instanceof ServiceRegistration) {
			ServiceRegistration service = (ServiceRegistration) element;

			List folders = new ArrayList();

			if (service.getProperties().length > 0)
				folders.add(new Folder(Folder.F_PROPERTIES, service));
			if (service.getUsingBundleIds().length > 0)
				folders.add(new Folder(Folder.F_USING_BUNDLES, service));

			return folders.toArray();
		}

		if (element instanceof Object[]) {
			return (Object[]) element;
		}

		return null;
	}

	public Object getParent(Object element) {
		if (!(element instanceof ModelObject)) {
			return null;
		}

		if (element instanceof Folder) {
			return ((Folder) element).getParent();
		}

		return null;
	}

	public boolean hasChildren(Object element) {
		Object[] children = getChildren(element);
		return children != null && children.length > 0;
	}

	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) { // do nothing
	}

}
