/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.pde.api.tools.internal.model.BundleComponent;
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFactory;
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFilter;
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin;
import org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblemFilter;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A generic {@link IApiFilterStore} that does not depend on workspace resources
 * to filter {@link IApiProblem}s.
 * <br>
 * This filter store can have filters added and removed from it, but those changes 
 * are not saved.
 */
public class FilterStore implements IApiFilterStore {

	public static final String GLOBAL = "!global!"; //$NON-NLS-1$
	/**
	 * Represents no filters
	 */
	public static IApiProblemFilter[] NO_FILTERS = new IApiProblemFilter[0];
	/**
	 * The current version of this filter store file format
	 */
	public static final int CURRENT_STORE_VERSION = 2;
	/**
	 * Constant representing the name of the .settings folder
	 */
	static final String SETTINGS_FOLDER = ".settings"; //$NON-NLS-1$
	/**
	 * The mapping of filters for this store.
	 */
	protected HashMap fFilterMap = null;
	/**
	 * The bundle component backing this store
	 */
	private BundleComponent fComponent = null;

	/**
	 * Constructor
	 */
	public FilterStore() {}
	
	/**
	 * Constructor
	 * @param component
	 */
	public FilterStore(BundleComponent component) {
		fComponent = component;
		initializeApiFilters();
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#addFilters(org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblemFilter[])
	 */
	public void addFilters(IApiProblemFilter[] filters) {
		if(filters != null && filters.length > 0) {
			initializeApiFilters();
			for (int i = 0; i < filters.length; i++) {
				fFilterMap.put(filters[i].getComponentId(), filters[i]);
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#addFiltersFor(org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem[])
	 */
	public void addFiltersFor(IApiProblem[] problems) {
		if(problems != null && problems.length > 0) {
			initializeApiFilters();
			internalAddFilters(problems, null);
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#getFilters(org.eclipse.core.resources.IResource)
	 */
	public IApiProblemFilter[] getFilters(IResource resource) {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#getResources()
	 */
	public IResource[] getResources() {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#removeFilters(org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblemFilter[])
	 */
	public boolean removeFilters(IApiProblemFilter[] filters) {
		if(filters != null && filters.length > 0) {
			initializeApiFilters();
			boolean removed = true;
			for(int i = 0; i < filters.length; i++) {
				removed &= (fFilterMap.remove(filters[i].getComponentId()) != null);
			}
			return removed;
		}
		return false;
	}

	/**
	 * Loads the filters from the .api_filters file
	 */
	protected synchronized void initializeApiFilters() {
		if(fFilterMap == null) {
			fFilterMap = new HashMap(5);
			ZipFile jarFile = null;
			InputStream filterstream = null;
			File loc = new File(fComponent.getLocation());
			String extension = new Path(loc.getName()).getFileExtension();
			try {
				if (extension != null && extension.equals("jar") && loc.isFile()) { //$NON-NLS-1$
					jarFile = new ZipFile(loc, ZipFile.OPEN_READ);
					ZipEntry filterfile = jarFile.getEntry(IApiCoreConstants.API_FILTERS_XML_NAME);
					if (filterfile != null) {
						filterstream = jarFile.getInputStream(filterfile);
					}
				} else {
					File file = new File(loc, SETTINGS_FOLDER+File.separator+IApiCoreConstants.API_FILTERS_XML_NAME);
					if (file.exists()) {
						filterstream = new FileInputStream(file);
					}
				}
				if (filterstream == null) {
					return;
				}
				readFilterFile(filterstream);
				
			} catch (IOException e) {
				ApiPlugin.log(e);
			} finally {
				fComponent.closingZipFileAndStream(filterstream, jarFile);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#isFiltered(org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem)
	 */
	public boolean isFiltered(IApiProblem problem) {
		if (fFilterMap == null || fFilterMap.isEmpty()) {
			return false;
		}
		String typeName = problem.getTypeName();
		if (typeName == null || typeName.length() == 0) {
			typeName = GLOBAL;
		}
		Set filters = (Set) fFilterMap.get(typeName);
		if (filters == null) {
			return false;
		}
		for (Iterator iterator = filters.iterator(); iterator.hasNext();) {
			IApiProblemFilter filter = (IApiProblemFilter) iterator.next();
			if (problemsMatch(filter.getUnderlyingProblem(), problem)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Returns <code>true</code> if the attributes of the problems match, <code>false</code> otherwise
	 * 
	 * @param filterProblem the problem from the filter store
	 * @param problem the problem from the builder
	 * @return <code>true</code> if the problems match, <code>false</code> otherwise
	 */
	protected boolean problemsMatch(IApiProblem filterProblem, IApiProblem problem) {
		if (problem.getId() == filterProblem.getId()) {
			// check arguments
//			String problemPath = problem.getResourcePath();
//			String filterProblemPath = filterProblem.getResourcePath();
//			if (problemPath == null) {
//				if (filterProblemPath != null) {
//					return false;
//				}
//			} else if (filterProblemPath == null) {
//				return false;
//			} else if (!new Path(problemPath).equals(new Path(filterProblemPath))) {
//				return false;
//			}
			String problemTypeName = problem.getTypeName();
			String filterProblemTypeName = filterProblem.getTypeName();
			if (problemTypeName == null) {
				if (filterProblemTypeName != null) {
					return false;
				}
			} else if (filterProblemTypeName == null) {
				return false;
			} else if (!problemTypeName.equals(filterProblemTypeName)) {
				return false;
			}
			return argumentsEquals(problem.getMessageArguments(), filterProblem.getMessageArguments());
		}
		return false;
	}
	
	/**
	 * Returns if the arrays of message arguments are equal. 
	 * <br>
	 * The arrays are considered equal iff:
	 * <ul>
	 * <li>both are <code>null</code></li>
	 * <li>both are the same length</li>
	 * <li>both have equal elements at equal positions in the array</li>
	 * </ul>
	 * @param problemMessageArguments
	 * @param filterProblemMessageArguments
	 * @return <code>true</code> if the arrays are equal, <code>false</code> otherwise
	 */
	private boolean argumentsEquals(String[] problemMessageArguments, String[] filterProblemMessageArguments) {
		// filter problems message arguments are always simple name
		// problem message arguments are fully qualified name outside the IDE
		int length = problemMessageArguments.length;
		if (length == filterProblemMessageArguments.length) {
			for (int i = 0; i < length; i++) {
				String problemMessageArgument = problemMessageArguments[i];
				String filterProblemMessageArgument = filterProblemMessageArguments[i];
				if (problemMessageArgument.equals(filterProblemMessageArgument)) {
					continue;
				}
				int index = problemMessageArgument.lastIndexOf('.');
				int filterProblemIndex = filterProblemMessageArgument.lastIndexOf('.');
				if (index == -1) {
					if (filterProblemIndex == -1) {
						return false; // simple names should match
					}
					if (filterProblemMessageArgument.substring(filterProblemIndex + 1).equals(problemMessageArgument)) {
						continue;
					} else {
						return false;
					}
				} else if (filterProblemIndex != -1) {
					return false; // fully qualified name should match
				} else {
					if (problemMessageArgument.substring(index + 1).equals(filterProblemMessageArgument)) {
						continue;
					} else {
						return false;
					}
				}
			}
			return true;
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.IApiFilterStore#dispose()
	 */
	public void dispose() {
		if(fFilterMap != null) {
			fFilterMap.clear();
			fFilterMap = null;
		}
	}
	
	/**
	 * Reads the API problem filter file and calls back to {@link #addFilters(IApiProblemFilter[])} 
	 * to store the filters.
	 * <br>
	 * This method will not close the given input stream when done reading it.
	 * 
	 * @param contents the {@link InputStream} for the contents of the filter file, <code>null</code> is not allowed.
	 * @throws IOException if the stream cannot be read or fails
	 */
	protected void readFilterFile(InputStream contents) throws IOException {
		if(contents == null) {
			throw new IOException(CoreMessages.FilterStore_0);
		}
		String xml = new String(Util.getInputStreamAsCharArray(contents, -1, IApiCoreConstants.UTF_8));
		Element root = null;
		try {
			root = Util.parseDocument(xml);
		}
		catch(CoreException ce) {
			ApiPlugin.log(ce);
		}
		if (root == null) {
			return;
		}
		if (!root.getNodeName().equals(IApiXmlConstants.ELEMENT_COMPONENT)) {
			return;
		}
		String component = root.getAttribute(IApiXmlConstants.ATTR_ID);
		if(component.length() == 0) {
			return;
		}
		String versionValue = root.getAttribute(IApiXmlConstants.ATTR_VERSION);
		int currentVersion = Integer.parseInt(IApiXmlConstants.API_FILTER_STORE_CURRENT_VERSION);
		int version = 0;
		if(versionValue.length() != 0) {
			try {
				version = Integer.parseInt(versionValue);
			} catch (NumberFormatException e) {
				// ignore
			}
		}
		if (version != currentVersion) {
			return;
		}
		NodeList resources = root.getElementsByTagName(IApiXmlConstants.ELEMENT_RESOURCE);
		ArrayList newfilters = new ArrayList();
		ArrayList comments = new ArrayList();
		for(int i = 0; i < resources.getLength(); i++) {
			Element element = (Element) resources.item(i);
			String typeName = element.getAttribute(IApiXmlConstants.ATTR_TYPE);
			if (typeName.length() == 0) {
				// if there is no type attribute, an empty string is returned
				typeName = null;
			}
			String path = element.getAttribute(IApiXmlConstants.ATTR_PATH);
			NodeList filters = element.getElementsByTagName(IApiXmlConstants.ELEMENT_FILTER);
			for(int j = 0; j < filters.getLength(); j++) {
				element = (Element) filters.item(j);
				int id = loadIntegerAttribute(element, IApiXmlConstants.ATTR_ID);
				if(id <= 0) {
					continue;
				}
				String[] messageargs = null;
				NodeList elements = element.getElementsByTagName(IApiXmlConstants.ELEMENT_PROBLEM_MESSAGE_ARGUMENTS);
				if (elements.getLength() != 1) continue;
				Element messageArguments = (Element) elements.item(0);
				NodeList arguments = messageArguments.getElementsByTagName(IApiXmlConstants.ELEMENT_PROBLEM_MESSAGE_ARGUMENT);
				int length = arguments.getLength();
				messageargs = new String[length];
				String comment = element.getAttribute(IApiXmlConstants.ATTR_COMMENT);
				comments.add((comment.length() < 1 ? null : comment));
				for (int k = 0; k < length; k++) {
					Element messageArgument = (Element) arguments.item(k);
					messageargs[k] = messageArgument.getAttribute(IApiXmlConstants.ATTR_VALUE);
				}
				newfilters.add(ApiProblemFactory.newApiProblem(path, typeName, messageargs, null, null, -1, -1, -1, id));
			}
		}
		internalAddFilters(
				(IApiProblem[]) newfilters.toArray(new IApiProblem[newfilters.size()]),
				(String[]) comments.toArray(new String[comments.size()]));
		newfilters.clear();
	}
	
	/**
	 * Internal use method that allows auto-persisting of the filter file to be turned on or off
	 * @param problems the problems to add the the store
	 * @param persist if the filters should be auto-persisted after they are added
	 */
	protected void internalAddFilters(IApiProblem[] problems, String[] comments) {
		if(problems == null) {
			return;
		}
		for(int i = 0; i < problems.length; i++) {
			IApiProblem problem = problems[i];
			IApiProblemFilter filter = new ApiProblemFilter(fComponent.getSymbolicName(), problem, comments[i]);
			String typeName = problem.getTypeName();
			if (typeName == null) {
				typeName = GLOBAL;
			}
			Set filters = (Set) fFilterMap.get(typeName);
			if(filters == null) {
				filters = new HashSet();
				fFilterMap.put(typeName, filters);
			}
			filters.add(filter);
		}
	}
	
	/**
	 * Loads the specified integer attribute from the given XML element
	 * @param element the XML element
	 * @param name the name of the attribute
	 * @return the specified value in XML or -1
	 */
	protected int loadIntegerAttribute(Element element, String name) {
		String value = element.getAttribute(name);
		if(value.length() == 0) {
			return -1;
		}
		try {
			int number = Integer.parseInt(value);
			return number;
		}
		catch(NumberFormatException nfe) {}
		return -1;
	}

}