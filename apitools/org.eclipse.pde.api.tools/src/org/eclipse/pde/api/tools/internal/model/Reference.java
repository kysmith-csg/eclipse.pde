/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.internal.model;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.pde.api.tools.internal.provisional.IApiComponent;
import org.eclipse.pde.api.tools.internal.provisional.IClassFile;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiMember;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiMethod;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiType;
import org.eclipse.pde.api.tools.internal.provisional.model.IReference;
import org.eclipse.pde.api.tools.internal.provisional.search.ReferenceModifiers;
import org.eclipse.pde.api.tools.internal.util.Util;

/**
 * Implementation of a reference from one member to another.
 * 
 * @since 1.1
 */
public class Reference implements IReference {
	
	/**
	 * Line number where the reference occurred.
	 */
	private int fSourceLine = -1;
	
	/**
	 * Member where the reference occurred.
	 */
	private IApiMember fSourceMember;
	
	/**
	 * One of the valid {@link org.eclipse.pde.api.tools.internal.provisional.search.ReferenceModifiers}
	 */
	private int fKind;
	
	/**
	 * One of the valid type, method, field.
	 */
	private int fType;
	
	/**
	 * Name of the referenced type
	 */
	private String fTypeName;
	
	/**
	 * Name of the referenced member or <code>null</code>
	 */
	private String fMemberName;
	
	/**
	 * Signature of the referenced method or <code>null</code>
	 */
	private String fSignature;
	
	/**
	 * Resolved reference or <code>null</code>
	 */
	private IApiMember fResolved;
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getLineNumber()
	 */
	public int getLineNumber() {
		return fSourceLine;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getMember()
	 */
	public IApiMember getMember() {
		return fSourceMember;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferenceKind()
	 */
	public int getReferenceKind() {
		return fKind;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferenceType()
	 */
	public int getReferenceType() {
		return fType;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferencedMember()
	 */
	public IApiMember getResolvedReference() {
		return fResolved;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferencedMemberName()
	 */
	public String getReferencedMemberName() {
		return fMemberName;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferencedSignature()
	 */
	public String getReferencedSignature() {
		return fSignature;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.model.IReference#getReferencedTypeName()
	 */
	public String getReferencedTypeName() {
		return fTypeName;
	}

	/**
	 * Creates and returns a method reference.
	 * 
	 * @param origin where the reference occurred from
	 * @param typeName name of the referenced type where virtual method lookup begins
	 * @param methodName name of the referenced method
	 * @param signature signature of the referenced method
	 * @param kind kind of method reference
	 */
	public static Reference methodReference(IApiMember origin, String typeName, String methodName, String signature, int kind) {
		Reference ref = new Reference();
		ref.fSourceMember = origin;
		ref.fTypeName = typeName;
		ref.fMemberName = methodName;
		ref.fSignature = signature;
		ref.fKind = kind;
		ref.fType = IReference.T_METHOD_REFERENCE;
		return ref;
	}
	
	/**
	 * Creates and returns a field reference.
	 * 
	 * @param origin where the reference occurred from
	 * @param typeName name of the referenced type where field lookup begins
	 * @param fieldName name of the referenced field
	 * @param kind kind of field reference
	 */
	public static Reference fieldReference(IApiMember origin, String typeName, String fieldName, int kind) {
		Reference ref = new Reference();
		ref.fSourceMember = origin;
		ref.fTypeName = typeName;
		ref.fMemberName = fieldName;
		ref.fKind = kind;
		ref.fType = IReference.T_FIELD_REFERENCE;
		return ref;
	}	
	
	/**
	 * Creates and returns a type reference.
	 * 
	 * @param origin where the reference occurred from
	 * @param typeName name of the referenced type
	 * @param kind kind of reference
	 */
	public static Reference typeReference(IApiMember origin, String typeName, int kind) {
		Reference ref = new Reference();
		ref.fSourceMember = origin;
		ref.fTypeName = typeName;
		ref.fKind = kind;
		ref.fType = IReference.T_TYPE_REFERENCE;
		return ref;
	}	
	
	/**
	 * Creates and returns a type reference.
	 * 
	 * @param origin where the reference occurred from
	 * @param typeName name of the referenced type
	 * @param signature extra type signature information
	 * @param kind kind of reference
	 */
	public static Reference typeReference(IApiMember origin, String typeName, String signature, int kind) {
		Reference ref = typeReference(origin, typeName, kind);
		ref.fSignature = signature;
		return ref;
	}	
	
	/**
	 * Sets the line number - used by the reference extractor.
	 * 
	 * @param line line number
	 */
	void setLineNumber(int line) {
		fSourceLine = line;
	}
	
	/**
	 * Resolves this reference in the given profile.
	 * 
	 * @param engine search engine resolving the reference
	 * @throws CoreException
	 */
	public void resolve() throws CoreException {
		if (fResolved == null) {
			IApiComponent sourceComponent = getMember().getApiComponent();
			if(sourceComponent != null) {
				IClassFile result = Util.getClassFile(
						sourceComponent.getProfile().resolvePackage(sourceComponent, Util.getPackageName(getReferencedTypeName())),
						getReferencedTypeName());
				if(result != null) {
					IApiType type = result.getStructure();
					switch (getReferenceType()) {
					case IReference.T_TYPE_REFERENCE:
						fResolved = type;
						break;
					case IReference.T_FIELD_REFERENCE:
						fResolved = type.getField(getReferencedMemberName());
						break;
					case IReference.T_METHOD_REFERENCE:
						resolveVirtualMethod(type, getReferencedMemberName(), getReferencedSignature());
						break;
					}
				}
			}
		}
		// TODO: throw exception on failure
	}	
	
	/**
	 * Resolves a virtual method and returns whether the method lookup was successful.
	 * We need to resolve the actual type that implements the method - i.e. do the virtual
	 * method lookup.
	 * 
	 * @param callSiteComponent the component where the method call site was located
	 * @param typeName referenced type name
	 * @param methodName referenced method name
	 * @param methodSignature referenced method signature
	 * @returns whether the lookup succeeded
	 * @throws CoreException if something goes terribly wrong
	 */
	private boolean resolveVirtualMethod(IApiType type, String methodName, String methodSignature) throws CoreException {
		IApiMethod target = type.getMethod(methodName, methodSignature);
		if (target != null) {
			if (target.isSynthetic()) {
				// don't resolve references to synthetic methods
				return false;
			} else {
				fResolved = target;
				return true;
			}
		}
		if (getReferenceKind() == ReferenceModifiers.REF_INTERFACEMETHOD) {
			// resolve method in super interfaces rather than class
			IApiType[] interaces = type.getSuperInterfaces();
			if (interaces != null) {
				for (int i = 0; i < interaces.length; i++) {
					if (resolveVirtualMethod(interaces[i], methodName, methodSignature)) {
						return true;
					}
				}
			}
		} else {
			IApiType superT = type.getSuperclass();
			if (superT != null) {
				return resolveVirtualMethod(superT, methodName, methodSignature);
			}
		}
		return false;
	}		
	
	/**
	 * Used by the search engine when resolving multiple references.
	 * 
	 * @param resolution resolved reference
	 */
	public void setResolution(IApiMember resolution) {
		fResolved = resolution;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("From: "); //$NON-NLS-1$
		IApiMember member = getMember();
		buf.append(member.getHandle().toString());
		if (getResolvedReference() == null) {
			buf.append("\nUnresoled To: "); //$NON-NLS-1$
			buf.append(getReferencedTypeName());
			if (getReferencedMemberName() != null) {
				buf.append('#');
				buf.append(getReferencedMemberName());
			}
			if (getReferencedSignature() != null) {
				buf.append('#');
				buf.append(getReferencedSignature());
			}
		} else {
			buf.append("\nResolved To: "); //$NON-NLS-1$
			buf.append(getResolvedReference().getHandle().toString());
		}
		buf.append("\nKind: "); //$NON-NLS-1$
		buf.append(Util.getReferenceKind(getReferenceKind()));
		return buf.toString();
	}
}
