/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.ui.internal.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin;
import org.eclipse.pde.api.tools.internal.provisional.IApiJavadocTag;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.eclipse.pde.api.tools.ui.internal.ApiUIPlugin;
import org.eclipse.swt.graphics.Image;

/**
 * This class creates completion proposals to javadoc header blocks
 * for the javadoc tags contributed via the apiJavadocTags extension point.
 * 
 * @see IApiJavadocTag
 * @see JavadocTagManager
 * @see APIToolsJavadocCompletionProposal
 * 
 * @since 1.0.0
 */
public class APIToolsJavadocCompletionProposalComputer implements IJavaCompletionProposalComputer {
	
	private String fErrorMessage = null;
	private Image fImageHandle = null;
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer#computeCompletionProposals(org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public List computeCompletionProposals(ContentAssistInvocationContext context, IProgressMonitor monitor) {
		if(context instanceof JavaContentAssistInvocationContext) {
			JavaContentAssistInvocationContext jcontext = (JavaContentAssistInvocationContext) context;
			IJavaProject project = jcontext.getProject();
			if(project == null || !Util.isApiProject(project)) {
				return Collections.EMPTY_LIST;
			}
			CompletionContext corecontext = jcontext.getCoreContext();
			if(corecontext.isInJavadoc()) {
				ICompilationUnit cunit = jcontext.getCompilationUnit();
				if(cunit != null) {
					ArrayList list = new ArrayList();
					int offset = jcontext.getInvocationOffset();
					try {
						IJavaElement element = cunit.getElementAt(offset);
						if (element == null) {
							return Collections.EMPTY_LIST;
						}
						ImageDescriptor imagedesc = jcontext.getLabelProvider().createImageDescriptor(org.eclipse.jdt.core.CompletionProposal.create(org.eclipse.jdt.core.CompletionProposal.JAVADOC_BLOCK_TAG, offset));
						fImageHandle = (imagedesc == null ? null : imagedesc.createImage());
						int type = getType(element);
						int member = IApiJavadocTag.MEMBER_NONE;
						boolean isabstract = false;
						switch(element.getElementType()) {
							case IJavaElement.METHOD: {
								IMethod method = (IMethod) element;
								if(method.isConstructor()) {
									member = IApiJavadocTag.MEMBER_CONSTRUCTOR;
								}
								else {
									member = IApiJavadocTag.MEMBER_METHOD;
								}
								break;
							}
							case IJavaElement.FIELD: {
								IField field  = (IField) element;
								if(Flags.isFinal(field.getFlags())) {
									return Collections.EMPTY_LIST;
								}
								member = IApiJavadocTag.MEMBER_FIELD;
								break;
							}
							case IJavaElement.TYPE: {
								isabstract = Flags.isAbstract(((IType) element).getFlags());
							}
						}
						IApiJavadocTag[] tags = ApiPlugin.getJavadocTagManager().getTagsForType(type, member);
						String completiontext = null;
						int tokenstart = corecontext.getTokenStart();
						int length = offset - tokenstart;
						for(int i = 0; i < tags.length; i++) {
							if(isabstract && tags[i].getTagName().equals("@noinstantiate")) {//$NON-NLS-1$
								continue;
							}
							completiontext = tags[i].getCompleteTag(type, member);
							if(appliesToContext(jcontext.getDocument(), completiontext, tokenstart, (length > 0 ? length : 1))) {
								list.add(new APIToolsJavadocCompletionProposal(corecontext, completiontext, tags[i].getTagName(), fImageHandle));
							}
						}
						return list;
					} 
					catch (JavaModelException e) {
						fErrorMessage = e.getMessage();
					}
				}
			}
		}
		return Collections.EMPTY_LIST;
	}
	
	/**
	 * Returns the type of the enclosing type.
	 * 
	 * @param element java element
	 * @return TYPE_INTERFACE, TYPE_CLASS or -1 
	 * @throws JavaModelException
	 */
	private int getType(IJavaElement element) throws JavaModelException {
		while (element != null && element.getElementType() != IJavaElement.TYPE) {
			element = element.getParent();
		}
		if (element instanceof IType) {
			IType type = (IType) element;
			if (type.isInterface()) {
				return IApiJavadocTag.TYPE_INTERFACE;
			}
		}
		return IApiJavadocTag.TYPE_CLASS;
	}
	
	/**
	 * Determines if the specified completion applies to the current offset context in the document
	 * @param document
	 * @param completiontext
	 * @param offset
	 * @return true if the completion applies, false otherwise
	 */
	private boolean appliesToContext(IDocument document, String completiontext, int tokenstart, int length) {
		if(length > completiontext.length()) {
			return false;
		}
		try {
			String prefix = document.get(tokenstart, length);
			return prefix.equals(completiontext.substring(0, length));
		}
		catch (BadLocationException e) {
			ApiUIPlugin.log(e);
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer#computeContextInformation(org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public List computeContextInformation(ContentAssistInvocationContext context, IProgressMonitor monitor) {
		return Collections.EMPTY_LIST;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer#getErrorMessage()
	 */
	public String getErrorMessage() {
		return fErrorMessage;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer#sessionEnded()
	 */
	public void sessionEnded() {
		if(fImageHandle != null) {
			fImageHandle.dispose();
		}
		fErrorMessage = null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer#sessionStarted()
	 */
	public void sessionStarted() {
		fErrorMessage = null;
	}

}
