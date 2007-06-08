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
package org.eclipse.pde.internal.ui.parts;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.service.resolver.VersionRange;
import org.eclipse.pde.internal.core.PDECoreMessages;
import org.eclipse.pde.internal.core.util.VersionUtil;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.pde.internal.ui.util.PDELabelUtility;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.osgi.framework.Version;

public class PluginVersionPart {

    private Text fMinVersionText;
    private Text fMaxVersionText;
    private Combo fMinVersionBound;
    private Combo fMaxVersionBound;
    
    private VersionRange fVersionRange;
    private boolean fIsRanged;
	private boolean fRangeAllowed;
	
	public PluginVersionPart(boolean rangeAllowed) {
		fRangeAllowed = rangeAllowed;
	}
	
    public void setVersion(String version) {
    	try {
        	if (version != null && !version.equals("")) { //$NON-NLS-1$
        		fVersionRange = new VersionRange(version);
        		Version max = fVersionRange.getMaximum();
        		if (max.getMajor() != Integer.MAX_VALUE && 
        				fVersionRange.getMinimum().compareTo(fVersionRange.getMaximum()) < 0)
        			fIsRanged = true;
        	} 
        } catch (IllegalArgumentException e) {
        	// illegal version string passed
        	fVersionRange = new VersionRange("[1.0.0,1.0.0]"); //$NON-NLS-1$
        }
	}
    
    public void createVersionFields(Composite comp, boolean createGroup, boolean editable) {
    	if (fRangeAllowed)
    	    createRangeField(comp, createGroup, editable);
        else
        	createSingleField(comp, createGroup, editable);
    	preloadFields();
    }
    
    private void createRangeField(Composite parent, boolean createGroup, boolean editable) {
    	if (createGroup) {
    		parent = new Group(parent, SWT.NONE);
    		((Group)parent).setText(getGroupText());
    		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    		parent.setLayout(new GridLayout(3, false));
    	}
    	String[] comboItems = new String[] {PDEUIMessages.DependencyPropertiesDialog_comboInclusive, PDEUIMessages.DependencyPropertiesDialog_comboExclusive};
	    Label minlabel = new Label(parent, SWT.NONE);
	    minlabel.setText(PDEUIMessages.DependencyPropertiesDialog_minimumVersion);
	    fMinVersionText = new Text(parent, SWT.SINGLE|SWT.BORDER);
	    fMinVersionText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	    fMinVersionText.setEnabled(editable);

	    fMinVersionBound = new Combo(parent, SWT.SINGLE|SWT.BORDER|SWT.READ_ONLY );
	    fMinVersionBound.setEnabled(editable);
	    fMinVersionBound.setItems(comboItems);
	    
	    Label maxlabel = new Label(parent, SWT.NONE);
	    maxlabel.setText(PDEUIMessages.DependencyPropertiesDialog_maximumVersion);
	    fMaxVersionText = new Text(parent, SWT.SINGLE|SWT.BORDER);
	    fMaxVersionText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	    fMaxVersionText.setEnabled(editable);
	    
	    fMaxVersionBound = new Combo(parent, SWT.SINGLE|SWT.BORDER|SWT.READ_ONLY);
	    fMaxVersionBound.setEnabled(editable);
	    fMaxVersionBound.setItems(comboItems);
    }

	private void createSingleField(Composite parent, boolean createGroup, boolean editable) {
		if (createGroup) {
			parent = new Group(parent, SWT.NONE);
			((Group)parent).setText(getGroupText());
			parent.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING|GridData.FILL_HORIZONTAL));
			parent.setLayout(new GridLayout(2, false));
		}
        Label label = new Label(parent, SWT.NONE);
        label.setText(PDEUIMessages.DependencyPropertiesDialog_version);
        
        fMinVersionText = new Text(parent, SWT.SINGLE|SWT.BORDER);
        fMinVersionText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        fMinVersionText.setEnabled(editable);
	}
	
	public void preloadFields() {
		if (fRangeAllowed) {
		    fMinVersionText.setText((fVersionRange != null) ? fVersionRange.getMinimum().toString() : ""); //$NON-NLS-1$
		    fMaxVersionText.setText((fVersionRange != null && fVersionRange.getMaximum().getMajor() != Integer.MAX_VALUE) ? fVersionRange.getMaximum().toString() : ""); //$NON-NLS-1$

		    if (fVersionRange != null)
		    	fMinVersionBound.select((fVersionRange.getIncludeMinimum()) ? 0 : 1);
		    else
		    	fMinVersionBound.select(0);
		    
		    if (fVersionRange != null && getMaxVersion().length() > 0)
		    	fMaxVersionBound.select((fVersionRange.getIncludeMaximum()) ? 0 : 1);
		    else
		    	fMaxVersionBound.select(1);
		}
        fMinVersionText.setText((fVersionRange != null) ? fVersionRange.getMinimum().toString() : ""); //$NON-NLS-1$
	}
	
	private IStatus validateVersion(String text, Text textWidget, boolean shortErrorMessage) {
    	if (text.length() == 0) return Status.OK_STATUS;
        if (VersionUtil.validateVersion(text).getSeverity() != IStatus.OK) {
        	String errorMessage = null;
        	if (shortErrorMessage) {
        		// For dialogs
        		errorMessage = PDEUIMessages.DependencyPropertiesDialog_invalidFormat;
        	} else {
        		// For everything else:  Field assist, wizards
        		errorMessage = PDECoreMessages.BundleErrorReporter_InvalidFormatInBundleVersion;
        	}
            return new Status(IStatus.ERROR, "org.eclipse.pde.ui", //$NON-NLS-1$
					IStatus.ERROR,
					PDELabelUtility.qualifyMessage(PDELabelUtility.getFieldLabel(textWidget), 
							errorMessage),
					null);
        }
        
        return Status.OK_STATUS;
    }
    
	private IStatus validateVersionRange(boolean shortErrorMessage) {
		if ((!fRangeAllowed && getMinVersion().length() == 0)
				|| (fRangeAllowed && (getMinVersion().length() == 0 || getMaxVersion().length() == 0))) {
			fIsRanged = false;
			return Status.OK_STATUS;
		}
		
    	String errorMessage = null;
    	if (shortErrorMessage) {
    		// For dialogs
    		errorMessage = PDEUIMessages.DependencyPropertiesDialog_invalidFormat;
    	} else {
    		// For everything else:  Field assist, wizards
    		errorMessage = PDECoreMessages.BundleErrorReporter_InvalidFormatInBundleVersion;
    	}		
		
		Version v1;
		Version v2;
		try {
			v1 = new Version(getMinVersion());
		} catch (IllegalArgumentException e) {
			return new Status(
					IStatus.ERROR,
					"org.eclipse.pde.ui",  //$NON-NLS-1$
					IStatus.ERROR, 
					PDELabelUtility.qualifyMessage(PDELabelUtility.getFieldLabel(fMinVersionText), 
							errorMessage), 
					null);
		}
		if (!fRangeAllowed) // version created fine
			return Status.OK_STATUS;

		try {
			v2 = new Version(getMaxVersion());
		} catch (IllegalArgumentException e) {
			return new Status(
					IStatus.ERROR,
					"org.eclipse.pde.ui",  //$NON-NLS-1$
					IStatus.ERROR, 
					PDELabelUtility.qualifyMessage(PDELabelUtility.getFieldLabel(fMaxVersionText), 
							errorMessage), 
					null); //$NON-NLS-1$;
		}
        if (v1.compareTo(v2) == 0 || v1.compareTo(v2) < 0) {
        	fIsRanged = true;
            return Status.OK_STATUS;
        }
		return new Status(IStatus.ERROR, 
				"org.eclipse.pde.ui",  //$NON-NLS-1$
				IStatus.ERROR, 
				PDEUIMessages.DependencyPropertiesDialog_versionRangeError, 
				null);
	}
    
	/**
	 * Short error messages are required for dialog status lines.  Long error
	 * messages are truncated and are not decorated with a status image.
	 * @param shortErrorMessage if <code>true</code>, a brief error message
	 * will be used.
	 * @return
	 */
	public IStatus validateFullVersionRangeText(boolean shortErrorMessage) {
    	IStatus status = validateVersion(getMinVersion(), fMinVersionText, shortErrorMessage);
    	if (status.isOK()) status = validateVersion(getMaxVersion(), fMaxVersionText, shortErrorMessage);
    	if (status.isOK()) status = validateVersionRange(shortErrorMessage);
        return status;
	}
	
	private String getMinVersion() {
		return fMinVersionText.getText().trim();
	}
	private String getMaxVersion() {
		if (fMaxVersionText != null)
			return fMaxVersionText.getText().trim();
		return ""; //$NON-NLS-1$
	}
	private boolean getMinInclusive() {
		if (fMinVersionBound != null)
			return fMinVersionBound.getSelectionIndex() == 0;
		return false;
	}
	private boolean getMaxInclusive() {
		if (fMaxVersionBound != null)
			return fMaxVersionBound.getSelectionIndex() == 0;
		return true;
	}
	
	private String extractSingleVersionFromText() {
		if (!fRangeAllowed) return getMinVersion();
		if (getMinVersion().length() == 0)
			return getMaxVersion();
		return getMinVersion();
	}
	
	public String getVersion() {
        String version;
		if (fIsRanged) {
        	// if versions are equal they must be inclusive for a range to be valid
        	// blindly set for the user
			String minV = getMinVersion();
			String maxV = getMaxVersion();
			boolean minI = getMinInclusive();
			boolean maxI = getMaxInclusive();
			if (minV.equals(maxV))
				minI = maxI = true;
        	version = new VersionRange(new Version(minV), minI, new Version(maxV), maxI).toString();
		} else {
        	String singleversion = extractSingleVersionFromText();
        	if (singleversion == null || singleversion.length() == 0)
        		version = ""; //$NON-NLS-1$
        	else
        		version = new Version(singleversion).toString(); 
        }
        return version;
	}
	
	public void addListeners(ModifyListener minListener, ModifyListener maxListener) {
		if (fMinVersionText != null && minListener != null)
			fMinVersionText.addModifyListener(minListener);
		if (fRangeAllowed && fMaxVersionText != null && maxListener != null)
			fMaxVersionText.addModifyListener(maxListener);
	}
	
	protected String getGroupText() {
		return PDEUIMessages.DependencyPropertiesDialog_groupText;
	}
}
