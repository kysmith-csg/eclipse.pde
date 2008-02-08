package org.eclipse.pde.api.tools.internal.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.util.IClassFileReader;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.api.tools.internal.ApiProfileManager;
import org.eclipse.pde.api.tools.internal.BundleApiComponent;
import org.eclipse.pde.api.tools.internal.comparator.Delta;
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin;
import org.eclipse.pde.api.tools.internal.provisional.Factory;
import org.eclipse.pde.api.tools.internal.provisional.IApiComponent;
import org.eclipse.pde.api.tools.internal.provisional.IApiMarkerConstants;
import org.eclipse.pde.api.tools.internal.provisional.IApiPreferenceConstants;
import org.eclipse.pde.api.tools.internal.provisional.IApiProfile;
import org.eclipse.pde.api.tools.internal.provisional.IClassFile;
import org.eclipse.pde.api.tools.internal.provisional.VisibilityModifiers;
import org.eclipse.pde.api.tools.internal.provisional.comparator.ApiComparator;
import org.eclipse.pde.api.tools.internal.provisional.comparator.DeltaProcessor;
import org.eclipse.pde.api.tools.internal.provisional.comparator.IDelta;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IElementDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IFieldDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IMethodDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IReferenceTypeDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.search.IApiSearchScope;
import org.eclipse.pde.api.tools.internal.provisional.search.ILocation;
import org.eclipse.pde.api.tools.internal.provisional.search.IReference;
import org.eclipse.pde.api.tools.internal.provisional.search.ReferenceModifiers;
import org.eclipse.pde.api.tools.internal.util.SinceTagVersion;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.osgi.framework.Version;

import com.ibm.icu.text.MessageFormat;

/**
 * Builder for creating api tooling resource markers
 * @since 1.0.0
 */
public class ApiToolBuilder extends IncrementalProjectBuilder {
	private static final int CONTAINS_API_BREAKAGE = 1;
	private static final int CONTAINS_API_CHANGES = 2;

	/**
	 * Constant representing the name of the 'source' attribute on api tooling markers.
	 * Value is <code>Api Tooling</code>
	 */
	private static final String SOURCE = "Api Tooling"; //$NON-NLS-1$
	
	/**
	 * Internal flag used to determine what created the marker, as there is overlap for reference kinds and deltas
	 */
	public static final int REF_TYPE_FLAG = 0;

	/**
	 * Constant used for controlling tracing in the api tool builder
	 */
	private static boolean DEBUG = Util.DEBUG;
	
	/**
	 * Method used for initializing tracing in the api tool builder
	 */
	public static void setDebug(boolean debugValue) {
		DEBUG = debugValue || Util.DEBUG;
	}
	
	/**
	 * The current project for which this builder was defined
	 */
	private IProject fCurrentProject;
	
	private int bits;
	
	static class SinceTagChecker extends ASTVisitor {
		private static final int ABORT = 0x01;
		private static final int MISSING = 0x02;
		private static final int HAS_JAVA_DOC = 0x04;
		private static final int HAS_NON_JAVA_DOC = 0x08;
		private static final int HAS_NO_COMMENT  = 0x10;

		private int nameStart;
		int bits;
		private String sinceVersion;
		private CompilationUnit fCompilationUnit;

		private SinceTagChecker(int nameStart) {
			this.nameStart = nameStart;
		}

		public boolean visit(CompilationUnit compilationUnit) {
			this.fCompilationUnit = compilationUnit;
			return true;
		}

		public boolean visit(VariableDeclarationFragment node) {
			if ((this.bits & ABORT) != 0) return false;
			if (node.getName().getStartPosition() == this.nameStart) {
				this.bits |= ABORT;
				ASTNode parent = node.getParent();
				if (parent.getNodeType() == ASTNode.FIELD_DECLARATION) {
					FieldDeclaration fieldDeclaration = (FieldDeclaration) parent;
					processJavadoc(fieldDeclaration);
				}
			}
			return false;
		}

		public boolean visit(EnumDeclaration node) {
			return visitAbstractTypeDeclaration(node);
		}

		public boolean visit(TypeDeclaration node) {
			return visitAbstractTypeDeclaration(node);
		}

		private boolean visitAbstractTypeDeclaration(AbstractTypeDeclaration declaration) {
			if ((this.bits & ABORT) != 0) {
				return false;
			}
			if (declaration.getName().getStartPosition() == this.nameStart) {
				this.bits |= ABORT;
				processJavadoc(declaration);
			}
			return true;
		}

		public boolean visit(AnnotationTypeDeclaration node) {
			return visitAbstractTypeDeclaration(node);
		}

		public boolean visit(MethodDeclaration node) {
			if ((this.bits & ABORT) != 0) {
				return false;
			}
			if (node.getName().getStartPosition() == this.nameStart) {
				this.bits |= ABORT;
				processJavadoc(node);
			}
			return false;
		}

		public boolean visit(AnnotationTypeMemberDeclaration node) {
			if ((this.bits & ABORT) != 0) {
				return false;
			}
			if (node.getName().getStartPosition() == this.nameStart) {
				this.bits |= ABORT;
				processJavadoc(node);
			}
			return false;
		}
		public boolean visit(Initializer node) {
			return false;
		}
		public boolean visit(EnumConstantDeclaration node) {
			if ((this.bits & ABORT) != 0) {
				return false;
			}
			if (node.getName().getStartPosition() == this.nameStart) {
				this.bits |= ABORT;
				processJavadoc(node);
			}
			return false;
		}
		
		private void processJavadoc(BodyDeclaration bodyDeclaration) {
			Javadoc javadoc = bodyDeclaration.getJavadoc();
			boolean found = false;
			if (javadoc != null) {
				this.bits |= HAS_JAVA_DOC;
				List tags = javadoc.tags();
				for (Iterator iterator = tags.iterator(); iterator.hasNext();) {
					TagElement element = (TagElement) iterator.next();
					if (TagElement.TAG_SINCE.equals(element.getTagName())) {
						// @since is present
						// check if valid
						List fragments = element.fragments();
						if (fragments.size() == 1) {
							found = true;
							ASTNode fragment = (ASTNode) fragments.get(0);
							if (fragment.getNodeType() == ASTNode.TEXT_ELEMENT) {
								this.sinceVersion = ((TextElement) fragment).getText();
							}
						}
						break;
					}
				}
				if (!found) {
					this.bits |= MISSING;
				}
			} else if (this.fCompilationUnit != null) {
				// we check if there is a block comment at the starting position of the body declaration
				List commentList = this.fCompilationUnit.getCommentList();
				if (commentList == null) {
					this.bits |= HAS_NO_COMMENT;
					return;
				}
				int extendedStartPosition = this.fCompilationUnit.getExtendedStartPosition(bodyDeclaration);
				BlockComment newBlockComment = bodyDeclaration.getAST().newBlockComment();
				newBlockComment.setSourceRange(extendedStartPosition, 1);
				int result = Collections.binarySearch(commentList, newBlockComment, new Comparator() {
					public int compare(Object o1, Object o2) {
						Comment comment1 = (Comment) o1;
						Comment comment2 = (Comment) o2;
						return comment1.getStartPosition() - comment2.getStartPosition();
					}
				});
				if (result > 0) {
					this.bits |= HAS_NON_JAVA_DOC;
				} else {
					this.bits |= HAS_NO_COMMENT;
				}
			} else {
				this.bits |= HAS_NO_COMMENT;
			}
		}
		
		public boolean isMissing() {
			return (this.bits & MISSING) != 0;
		}

		public boolean hasNoComment() {
			return (this.bits & HAS_NO_COMMENT) != 0;
		}

		public boolean hasJavadocComment() {
			return (this.bits & HAS_JAVA_DOC) != 0;
		}

		public String getSinceVersion() {
			if (this.sinceVersion != null)
				return this.sinceVersion.trim();
			return null;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IncrementalProjectBuilder#build(int, java.util.Map, org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		this.bits = 0;
		this.fCurrentProject = getProject();
		if (fCurrentProject == null || !fCurrentProject.isAccessible()
				|| !this.fCurrentProject.hasNature(JavaCore.NATURE_ID)) {
			return new IProject[0];
		}
		if (DEBUG) {
			System.out.println("\nStarting build of " + fCurrentProject.getName() //$NON-NLS-1$
				+ " @ " + new Date(System.currentTimeMillis())); //$NON-NLS-1$
		}
		IProgressMonitor localMonitor = SubMonitor.convert(monitor, BuilderMessages.ApiToolBuilder_0, 2);

		try {
			switch(kind) {
				case FULL_BUILD : {
					if (DEBUG) {
						System.out.println("Performing full build as requested by user"); //$NON-NLS-1$
					}
					buildAll(localMonitor);
					break;
				}
				case AUTO_BUILD :
				case INCREMENTAL_BUILD : {
					IResourceDelta delta = getDelta(fCurrentProject);
					if (delta == null) {
						if (DEBUG) {
							System.out.println("Performing full build since deltas are missing after incremental request"); //$NON-NLS-1$
						}
						buildAll(localMonitor);
					} else {
						if (DEBUG) {
							System.out.println("Found a delta: " + delta); //$NON-NLS-1$
						}
						buildDelta(delta);
					}
					break;
				}
			}
		} finally {
			localMonitor.done();
		}
		if (DEBUG) {
			System.out.println("Finished build of " + this.fCurrentProject.getName() //$NON-NLS-1$
				+ " @ " + new Date(System.currentTimeMillis())); //$NON-NLS-1$
		}
		checkDefaultProfileSet();
		return fCurrentProject.getReferencingProjects();
	}
	
	/**
	 * Checks to see if there is a default api profile set in the workspace,
	 * if not create a marker
	 */
	private void checkDefaultProfileSet() {
		try {
			if(ApiPlugin.getDefault().getApiProfileManager().getDefaultApiProfile() == null) {
				if(DEBUG) {
					System.out.println("No default api profile, adding marker to ["+fCurrentProject.getName()+"]"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				// first we clean up all existing api tooling markers for the current project
				cleanupMarker(this.fCurrentProject);
				IMarker[] markers = this.fCurrentProject.findMarkers(ApiPlugin.DEFAULT_API_PROFILE_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
				if (markers.length == 1) {
					// marker already exists. So we can simply return
					return;
				}
				IMarker marker = fCurrentProject.createMarker(ApiPlugin.DEFAULT_API_PROFILE_PROBLEM_MARKER);
				//TODO add this severity level to the pref page?
				marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
				marker.setAttribute(IMarker.MESSAGE, BuilderMessages.ApiToolBuilder_10);
			} else {
				// we want to make sure that existing markers are removed
				this.fCurrentProject.deleteMarkers(ApiPlugin.DEFAULT_API_PROFILE_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
			}
		}
		catch(CoreException e) {
			ApiPlugin.log(e);
		}
	}
	
	/**
	 * Performs a full build for the workspace
	 * @param monitor
	 */
	private void buildAll(IProgressMonitor monitor) {
		IProgressMonitor localMonitor = SubMonitor.convert(monitor, BuilderMessages.ApiToolBuilder_1, 3);
		IApiProfile profile = ApiPlugin.getDefault().getApiProfileManager().getDefaultApiProfile();
		if (profile == null) {
			return;
		}
		// retrieve all .class files from the current project
		cleanupMarker(this.fCurrentProject);
		IPluginModelBase currentModel = getCurrentModel();
		if (currentModel != null) {
			localMonitor.subTask(BuilderMessages.ApiToolBuilder_2);
			IApiProfile wsprofile = getWorkspaceProfile();
			localMonitor.worked(1);
			if (wsprofile == null) {
				if (DEBUG) {
					System.err.println("Could not retrieve a workspace profile"); //$NON-NLS-1$
				}
				return;
			}
			String id = currentModel.getBundleDescription().getSymbolicName();
			// Binary compatibility checks
			IApiComponent apiComponent = wsprofile.getApiComponent(id);
			if(apiComponent != null) {
				localMonitor.subTask(BuilderMessages.ApiToolBuilder_3);
				compareProfiles(profile.getApiComponent(id), apiComponent);
				localMonitor.worked(1);
				// API usage checks
				IApiSearchScope scope = Factory.newScope(new IApiComponent[]{apiComponent});
				localMonitor.subTask(BuilderMessages.ApiToolBuilder_4);
				checkApiUsage(wsprofile, apiComponent, scope, localMonitor);
				localMonitor.worked(1);
			}
		}
	}
	
	/**
	 * Checks for illegal API usage in the specified component, creating problem
	 * markers as required.
	 * 
	 * @param profile profile being analyzed
	 * @param component component being built
	 * @param scope scope being built
	 * @param monitor progress monitor
	 */
	private void checkApiUsage(IApiProfile profile, IApiComponent component, IApiSearchScope scope, IProgressMonitor monitor) {
		ApiUseAnalyzer analyzer = new ApiUseAnalyzer();
		try {
			long start = System.currentTimeMillis();
			IReference[] illegal = analyzer.findIllegalApiUse(profile, component, scope, monitor);
			long end = System.currentTimeMillis();
			if (DEBUG) {
				System.out.println("API usage scan: " + (end- start) + " ms\t" + illegal.length + " problems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}		
			if (illegal.length > 0) {
				IJavaProject javaProject = JavaCore.create(this.fCurrentProject);
				for (int i = 0; i < illegal.length; i++) {
					createMarkerFor(illegal[i], javaProject);
				}
			}
		} catch (CoreException e) {
			ApiPlugin.log(e.getStatus());
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IncrementalProjectBuilder#clean(org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected void clean(IProgressMonitor monitor) throws CoreException {
		fCurrentProject = getProject();
		// clean up all existing markers
		cleanupMarker(fCurrentProject);
		//clean up the .api_settings
		cleanupApiProfileSettings(fCurrentProject);
	}

	/**
	 * Cleans the .api_settings file for the given project
	 * @param project
	 */
	private void cleanupApiProfileSettings(IProject project) {
		if(project != null && project.exists()) {
			IPath path = ApiPlugin.getDefault().getStateLocation().append(project.getName()).append(BundleApiComponent.API_DESCRIPTION_XML_NAME);
			File file = new File(path.toOSString());
			if(file.exists()) {
				file.delete();
			}
		}
	}
	
	/**
	 * @return the workspace {@link IApiProfile}
	 */
	private IApiProfile getWorkspaceProfile() {
		return ((ApiProfileManager)ApiPlugin.getDefault().getApiProfileManager()).getWorkspaceProfile();
	}
	
	/**
	 * @return the current {@link IPluginModelBase} based on the current project for this builder
	 */
	private IPluginModelBase getCurrentModel() {
		IPluginModelBase[] workspaceModels = PluginRegistry.getWorkspaceModels();
		IPath location = fCurrentProject.getLocation();
		IPluginModelBase currentModel = null;
		loop: for (int i = 0, max = workspaceModels.length; i < max; i++) {
			Path path = new Path(workspaceModels[i].getBundleDescription().getLocation());
			if (path.equals(location)) {
				currentModel = workspaceModels[i];
				break loop;
			}
		}
		return currentModel;
	}

	/**
	 * Builds an api delta using the default profile (from the workspace settings and the current
	 * workspace profile
	 * @param delta
	 */
	private void buildDelta(IResourceDelta delta) {
		IApiProfile profile = ApiPlugin.getDefault().getApiProfileManager().getDefaultApiProfile();
		if (profile == null) {
			return;
		}
		List flattenDeltas = new ArrayList();
		flatten0(delta, flattenDeltas);
		List typesToBeChecked = new ArrayList();
		for (Iterator iterator = flattenDeltas.iterator(); iterator.hasNext();) {
			IResourceDelta resourceDelta= (IResourceDelta) iterator.next();
			if (DEBUG) {
				switch(resourceDelta.getKind()) {
					case IResourceDelta.ADDED :
						System.out.print("ADDED"); //$NON-NLS-1$
						break;
					case IResourceDelta.CHANGED :
						System.out.print("CHANGED"); //$NON-NLS-1$
						break;
					case IResourceDelta.CONTENT :
						System.out.print("CONTENT"); //$NON-NLS-1$
						break;
					case IResourceDelta.REMOVED :
						System.out.print("REMOVED"); //$NON-NLS-1$
						break;
					case IResourceDelta.REPLACED :
						System.out.print("REPLACED"); //$NON-NLS-1$
						break;
				}
				System.out.print(" - "); //$NON-NLS-1$
			}
			IResource resource = resourceDelta.getResource();
			if (DEBUG) {
				System.out.println(resource);
			}
			IPath location = resource.getLocation();
			String fileName = location.lastSegment();
			if (Util.isClassFile(fileName) && resource.getType() == IResource.FILE) {
				typesToBeChecked.add(resource);
			}
		}
		if (typesToBeChecked.size() != 0) {
			IPluginModelBase currentModel = getCurrentModel();
			if (currentModel != null) {
				IApiProfile wsprofile = getWorkspaceProfile();
				if (wsprofile == null) {
					if (DEBUG) {
						System.err.println("Could not retrieve a workspace profile"); //$NON-NLS-1$
					}
					return;
				}
				String id = currentModel.getBundleDescription().getSymbolicName();
				IApiComponent apiComponent = wsprofile.getApiComponent(id);
				if(apiComponent == null) {
					return;
				}
				IApiComponent reference = profile.getApiComponent(id);
				List scopeElements = new ArrayList(); // build search scope for API usage scan
				for (Iterator iterator = typesToBeChecked.iterator(); iterator.hasNext(); ) {
					IFile file = (IFile) iterator.next();
					IClassFileReader classFileReader = ToolFactory.createDefaultClassFileReader(file.getLocation().toOSString(), IClassFileReader.SUPER_INTERFACES);
					if (classFileReader == null) continue;
					char[] className = classFileReader.getClassName();
					CharOperation.replace(className, '/', '.');
					if(reference != null) {
						compareProfiles(file, new String(className), reference,	apiComponent);
					}
					scopeElements.add(Util.getType(new String(className)));
				}
				checkApiUsage(wsprofile, apiComponent, Factory.newScope(apiComponent, (IElementDescriptor[]) scopeElements.toArray(new IElementDescriptor[scopeElements.size()])), null);
			}
		} else if (DEBUG) {
			System.out.println("No type to check"); //$NON-NLS-1$
		}
	}
	
	/**
	 * recursively flattens the given delta into a list of individual deltas
	 * @param delta
	 * @param flattenDeltas
	 */
	private void flatten0(IResourceDelta delta, List flattenDeltas) {
		IResourceDelta[] deltas = delta.getAffectedChildren();
		int length = deltas.length;
		if (length != 0) {
			for (int i = 0; i < length; i++) {
				flatten0(deltas[i], flattenDeltas);
			}
		} else {
			flattenDeltas.add(delta);
		}
	}

	private void compareProfiles(IResource resource, String typeName, IApiComponent reference, IApiComponent component) {
		IJavaProject javaProject = JavaCore.create(this.fCurrentProject);
		if (javaProject == null) return;
		ICompilationUnit compilationUnit = null;
		if (DEBUG) {
			System.out.println("BEFORE"); //$NON-NLS-1$
		}
		IResource compilationUnitResource = null;
		try {
			IType type = getType(javaProject, typeName);
			if (type != null) {
				compilationUnit = type.getCompilationUnit();
				if (compilationUnit != null) {
					compilationUnitResource = compilationUnit.getCorrespondingResource();
					if (compilationUnitResource != null) {
						cleanupMarker(compilationUnitResource);
						if (DEBUG) {
							IMarker[] markers = getMarkers(compilationUnitResource);
							if (markers == null) {
								// no marker created
							} else {
								for (int i = 0, max = markers.length; i < max; i++) {
									IMarker marker = markers[i];
									System.out.println(marker);
								}
							}
						}
					}
				}
			}
		} catch (JavaModelException e) {
			// ignore, we cannot create markers in this case
		}
		IClassFile classFile = null;
		try {
			classFile = component.findClassFile(typeName);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
		if (classFile == null) {
			if (DEBUG) System.err.println("Could not retrieve class file for " + typeName + " in " + component.getId()); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		IDelta delta = null;
		long time = System.currentTimeMillis();
		try {
			delta = ApiComparator.compare(
					classFile,
					reference,
					component,
					reference.getProfile(),
					component.getProfile(),
					VisibilityModifiers.API);
		} catch(Exception e) {
			ApiPlugin.log(e);
		} finally {
			if (DEBUG) System.out.println("Time spent for " + typeName + " : " + (System.currentTimeMillis() - time) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		if (delta == null) {
			if (DEBUG) System.err.println("An error occured while comparing"); //$NON-NLS-1$
			return;
		}
		if (delta != ApiComparator.NO_DELTA) {
			List allDeltas = Util.collectAllDeltas(delta);
			for (Iterator iterator = allDeltas.iterator(); iterator.hasNext();) {
				IDelta localDelta = (IDelta) iterator.next();
				processDelta(javaProject, localDelta, compilationUnit, reference, component);
			}
			checkApiComponentVersion(javaProject, reference, component);
			if (DEBUG) System.out.println("Complete"); //$NON-NLS-1$
		} else {
			if (DEBUG) {
				System.out.println("No delta"); //$NON-NLS-1$
			}
			cleanupVersionNumberingMarker();
		}
		if (DEBUG) {
			System.out.println("AFTER"); //$NON-NLS-1$
			if (compilationUnitResource != null) {
				IMarker[] markers = getMarkers(compilationUnitResource);
				if (markers == null) {
					// no marker created
					System.out.println("No marker created"); //$NON-NLS-1$
				} else {
					for (int i = 0, max = markers.length; i < max; i++) {
						IMarker marker = markers[i];
						System.out.println(marker);
					}
				}
			}
		}
	}

	private void checkApiComponentVersion(IJavaProject javaProject, IApiComponent reference, IApiComponent component) {
		int severityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_INCOMPATIBLE_API_COMPONENT_VERSION, javaProject);
		if ((this.bits & (CONTAINS_API_BREAKAGE | CONTAINS_API_CHANGES)) != 0
				&& severityLevel != ApiPlugin.SEVERITY_IGNORE) {
			String referenceVersionValue = reference.getVersion();
			String componentVersionValue = component.getVersion();
			Version referenceVersion = new Version(referenceVersionValue);
			Version componentVersion = new Version(componentVersionValue);
			if (DEBUG) {
				System.out.println("reference version of " + reference.getId() + " : " + referenceVersion); //$NON-NLS-1$ //$NON-NLS-2$
				System.out.println("component version of " + component.getId() + " : " + componentVersion); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if ((this.bits & CONTAINS_API_BREAKAGE) != 0) {
				// make sure that the major version has been incremented
				if (componentVersion.getMajor() <= referenceVersion.getMajor()) {
					Version newComponentVersion = new Version(
							componentVersion.getMajor() + 1,
							0,
							0,
							componentVersion.getQualifier());
					createVersionNumberingProblemMarkerMarker(
						NLS.bind(
							BuilderMessages.VersionManagementIncorrectMajorVersionForAPIBreakage,
							referenceVersionValue,
							componentVersionValue
						),
						severityLevel,
						true,
						String.valueOf(newComponentVersion));
				}
			} else {
				// only new API have been added
				if (componentVersion.getMajor() != referenceVersion.getMajor()) {
					// major version should be identical
					Version newComponentVersion = new Version(
							referenceVersion.getMajor(),
							componentVersion.getMinor() + 1,
							0,
							componentVersion.getQualifier());
					createVersionNumberingProblemMarkerMarker(
							NLS.bind(
								BuilderMessages.VersionManagementIncorrectMajorVersionForAPIChange,
								referenceVersionValue,
								componentVersionValue
							),
							severityLevel,
							false,
							String.valueOf(newComponentVersion));
				} else if (componentVersion.getMinor() <= referenceVersion.getMinor()) {
					// the minor version should be incremented
					Version newComponentVersion = new Version(
							componentVersion.getMajor(),
							componentVersion.getMinor() + 1,
							0,
							componentVersion.getQualifier());
					createVersionNumberingProblemMarkerMarker(
							NLS.bind(
								BuilderMessages.VersionManagementIncorrectMinorVersionForAPIChange,
								referenceVersionValue,
								componentVersionValue
							),
							severityLevel,
							false,
							String.valueOf(newComponentVersion));
				} else {
					// see if we should remove the marker
					cleanupVersionNumberingMarker();
				}
			}
		}
	}
	private void cleanupVersionNumberingMarker() {
		try {
			IMarker[] markers = this.fCurrentProject.findMarkers(ApiPlugin.BINARY_COMPATIBILITY_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
			IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
			if (manifestFile == null) return;
			IMarker[] manifestMarkers = manifestFile.findMarkers(ApiPlugin.VERSION_NUMBERING_PROBLEM_MARKER, false, IResource.DEPTH_ZERO);
			if (markers.length != 0) {
				// check if we already have such a version numbering marker
				if (manifestMarkers.length != 0) {
					// the existing marker will only be updated on full build
					return;
				}
			}
			switch(manifestMarkers.length) {
				case 0 :
					return;
				case 1 :
					IMarker marker = manifestMarkers[0];
					Object attribute = marker.getAttribute(IApiMarkerConstants.MARKER_ATTR_KIND);
					if (IApiMarkerConstants.MARKER_ATTR_MAJOR_VERSION_CHANGE.equals(attribute)) {
						marker.delete();
					}
					break;
				default:
					// delete all markers
					for (int i = 0, max = manifestMarkers.length; i < max; i++) {
						manifestMarkers[i].delete();
					}
			}
		} catch (CoreException e) {
			// ignore
		}
	}

	/**
	 * Cleans up the marker set for api tooling.
	 * @param resource
	 */
	private void cleanupMarker(IResource resource) {
		try {
			if (resource != null && resource.exists()) {
				resource.deleteMarkers(ApiPlugin.BINARY_COMPATIBILITY_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
				resource.deleteMarkers(ApiPlugin.API_USAGE_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
				resource.deleteMarkers(ApiPlugin.SINCE_TAGS_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
				if (resource.getType() == IResource.PROJECT) {
					// on full builds
					resource.deleteMarkers(ApiPlugin.VERSION_NUMBERING_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
				}
			}
		} catch(CoreException e) {
			ApiPlugin.log(e.getStatus());
		}
	}

	/**
	 * Returns the complete set of api tooling markers currently on the specified resource
	 * @param resource
	 * @return the complete set of api tooling markers
	 */
	private IMarker[] getMarkers(IResource resource) {
		try {
			if (resource != null && resource.exists()) {
				ArrayList markers = new ArrayList();
				markers.addAll(Arrays.asList(resource.findMarkers(ApiPlugin.BINARY_COMPATIBILITY_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE)));
				markers.addAll(Arrays.asList(resource.findMarkers(ApiPlugin.API_USAGE_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE)));
				markers.addAll(Arrays.asList(resource.findMarkers(ApiPlugin.VERSION_NUMBERING_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE)));
				markers.addAll(Arrays.asList(resource.findMarkers(ApiPlugin.SINCE_TAGS_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE)));
				return (IMarker[]) markers.toArray(new IMarker[markers.size()]);
			}
		} catch(CoreException e) {}
		return null;
	}

	private IType getType(IJavaProject javaProject, String typeName) throws JavaModelException {
		IType type = javaProject.findType(typeName);
		if (type != null) {
			return type;
		}
		if (typeName.indexOf('$') != -1) {
			// might be a member type
			return javaProject.findType(typeName.replace('$', '.'));
		}
		return null;
	}
	/**
	 * Returns if the given delta is filtered from creating a marker or not
	 * @param delta the delta to consider
	 * @param project the containing project
	 * @return true if the delta should not create a marker (is filtered) false otherwise
	 */
	private boolean isDeltaFiltered(IDelta delta, IJavaProject project) {
		StringBuffer kind = new StringBuffer();
		kind.append(Util.getDeltaKindName(delta));
		if(delta.getKind() != IDelta.ADDED_NOT_EXTEND_RESTRICTION &&
				delta.getKind() != IDelta.ADDED_NOT_IMPLEMENT_RESTRICTION) {
			if(delta.getFlags() > 0) {
				kind.append("_").append(Util.getDeltaFlagsName(delta)); //$NON-NLS-1$
			}
		}
		IElementDescriptor element = null;
		IMember member = Util.getIMember(delta, project);
		if(member == null) {
			return false;
		}
		try {
			switch(member.getElementType()) {
				case IJavaElement.FIELD: {
					IField field = (IField) member;
					element = Factory.fieldDescriptor(field.getDeclaringType().getFullyQualifiedName(), field.getElementName());
					break;
				}
				case IJavaElement.METHOD: {
					IMethod method = (IMethod) member;
					element = Factory.methodDescriptor(method.getDeclaringType().getFullyQualifiedName(), method.getElementName(), method.getSignature());
					break;
				}
				case IJavaElement.TYPE: {
					IType type = (IType) member;
					element = Factory.typeDescriptor(type.getFullyQualifiedName());
					break;
				}
			}
		}
		catch(JavaModelException e) {}
		if(element == null) {
			return false;
		}
		IApiComponent component = getWorkspaceProfile().getApiComponent(project.getElementName());
		if(component != null) {
			try {
				return component.getFilterStore().isFiltered(element, new String[] {kind.toString()});
			}
			catch(CoreException e) {
				ApiPlugin.log(e);
			}
		}
 		return false;
	}
	
	/**
	 * Returns if the given {@link IReference} should be filtered from having a problem marker created for it
	 * @param reference
	 * @return true if the {@link IReference} should not have a marker created, false otherwise
	 */
	private boolean isReferenceFiltered(IJavaProject project, IReference reference) {
		IApiComponent component = getWorkspaceProfile().getApiComponent(project.getElementName());
		if(component != null) {
			try {
				IElementDescriptor element = reference.getSourceLocation().getMember();
				return component.getFilterStore().isFiltered(element, new String[] {Util.getReferenceKind(reference.getReferenceKind())});
			}
			catch(CoreException e) {
				ApiPlugin.log(e);
			}
		}
		return false;
	}
	
	/**
	 * Creates a marker for the given delta on the backing resource of the specified 
	 * {@link ICompilationUnit}
	 * @param delta
	 * @param compilationUnit
	 * @param project
	 */
	private void createMarkerFor(IDelta delta, ICompilationUnit compilationUnit, IJavaProject project, IApiComponent reference, IApiComponent component) {
		if(isDeltaFiltered(delta, project)) {
			return;
		}
		this.bits |= CONTAINS_API_BREAKAGE;
		try {
			Version referenceVersion = new Version(reference.getVersion());
			Version componentVersion = new Version(component.getVersion());
			if (referenceVersion.getMajor() < componentVersion.getMajor()) {
				// API breakage are ok in this case
				return;
			}
			IResource correspondingResource = null;
			if (compilationUnit == null) {
				IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
				if (manifestFile == null) {
					// Cannot retrieve the manifest.mf file
					return;
				}
				correspondingResource = manifestFile;
			} else {
				correspondingResource = compilationUnit.getCorrespondingResource();
				if (correspondingResource == null) {
					return;
				}
			}
			String prefKey = Util.getDeltaPrefererenceKey(
					delta.getElementType(),
					delta.getKind(),
					delta.getFlags()); 
			int sev = ApiPlugin.getDefault().getSeverityLevel(prefKey, project);
			if (sev == ApiPlugin.SEVERITY_IGNORE) {
				// ignore
				return;
			}
			int severity = IMarker.SEVERITY_ERROR;
			if (sev == ApiPlugin.SEVERITY_WARNING) {
				severity = IMarker.SEVERITY_WARNING;
			}
			IMarker marker = correspondingResource.createMarker(ApiPlugin.BINARY_COMPATIBILITY_PROBLEM_MARKER);
			// retrieve line number, char start and char end
			int lineNumber = 1;
			int charStart = -1;
			int charEnd = 1;
			IMember member = Util.getIMember(delta, project);
			if (member != null) {
				ISourceRange range = member.getNameRange();
				charStart = range.getOffset();
				charEnd = charStart + range.getLength();
				try {
					IDocument document = Util.getDocument(compilationUnit);
					lineNumber = document.getLineOfOffset(charStart);
				} catch (BadLocationException e) {
					// ignore
				}
			}
			IJavaElement element = null;
			if (compilationUnit != null) {
				if(charStart > -1) {
					element = compilationUnit.getElementAt(charStart);
				} else {
					element = compilationUnit;
				}
			} else {
				element = project;
			}
			marker.setAttributes(
				new String[] {
						IMarker.MESSAGE,
						IMarker.SEVERITY,
						IMarker.SOURCE_ID,
						IMarker.LINE_NUMBER,
						IMarker.CHAR_START,
						IMarker.CHAR_END,
						IApiMarkerConstants.MARKER_ATTR_FLAGS,
						IApiMarkerConstants.MARKER_ATTR_KIND,
						IApiMarkerConstants.MARKER_ATT_HANDLE_ID
				},
				new Object[] {
						NLS.bind(BuilderMessages.ApiToolBuilder_5, delta.getMessage()),
						new Integer(severity),
						SOURCE,
						new Integer(lineNumber),
						new Integer(charStart < 0 ? 0 : charStart),
						new Integer(charEnd),
						new Integer(delta.getFlags()),
						new Integer(delta.getKind()),
						element.getHandleIdentifier()
				}
			);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
	}
	
	/**
	 * Creates a problem marker for the given illegal reference.
	 * 
	 * @param reference illegal reference
	 * @param project project the compilation unit is in
	 */
	private void createMarkerFor(IReference reference, IJavaProject project) {
		try {
			String message = null;
			String prefKey = null;
			switch(reference.getReferenceKind()) {
				case ReferenceModifiers.REF_IMPLEMENTS : {
					prefKey = ApiPlugin.RESTRICTION_NOIMPLEMENT;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_6, new String[] {reference.getTargetLocation().getType().getQualifiedName()});
					break;
				}
				case ReferenceModifiers.REF_EXTENDS : {
					prefKey = ApiPlugin.RESTRICTION_NOEXTEND;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_7, new String[] {reference.getTargetLocation().getType().getQualifiedName()});
					break;
				}
				case ReferenceModifiers.REF_INSTANTIATE : {
					prefKey = ApiPlugin.RESTRICTION_NOINSTANTIATE;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_8, new String[] {reference.getTargetLocation().getType().getQualifiedName()});
					break;
				}
				case ReferenceModifiers.REF_OVERRIDE : {
					IMethodDescriptor method = (IMethodDescriptor) reference.getTargetLocation().getMember();
					prefKey = ApiPlugin.RESTRICTION_NOEXTEND;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_9, new String[] {method.getEnclosingType().getQualifiedName(), Signature.toString(method.getSignature(), method.getName(), null, false, false)});
					break;
				}
				case ReferenceModifiers.REF_INTERFACEMETHOD :
				case ReferenceModifiers.REF_SPECIALMETHOD: 
				case ReferenceModifiers.REF_STATICMETHOD: 
				case ReferenceModifiers.REF_VIRTUALMETHOD: {
					IMethodDescriptor method = (IMethodDescriptor) reference.getTargetLocation().getMember();
					prefKey = ApiPlugin.RESTRICTION_NOREFERENCE;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_11, new String[] {method.getEnclosingType().getQualifiedName(), Signature.toString(method.getSignature(), method.getName(), null, false, false)});
					break;
				}
				case ReferenceModifiers.REF_GETFIELD :
				case ReferenceModifiers.REF_GETSTATIC :
				case ReferenceModifiers.REF_PUTFIELD :
				case ReferenceModifiers.REF_PUTSTATIC : {
					IFieldDescriptor field = (IFieldDescriptor) reference.getTargetLocation().getMember();
					prefKey = ApiPlugin.RESTRICTION_NOREFERENCE;
					message = MessageFormat.format(BuilderMessages.ApiToolBuilder_11, new String[] {field.getEnclosingType().getQualifiedName(), field.getName()});
					break;
				}
			}
			if(isReferenceFiltered(project, reference)) {
				return;
			}
			int sev = ApiPlugin.getDefault().getSeverityLevel(prefKey, project);
			if (sev == ApiPlugin.SEVERITY_IGNORE) {
				// ignore
				return;
			}
			int severity = IMarker.SEVERITY_ERROR;
			if (sev == ApiPlugin.SEVERITY_WARNING) {
				severity = IMarker.SEVERITY_WARNING;
			}
			ILocation location = reference.getSourceLocation();
			IReferenceTypeDescriptor refType = location.getType();
			String lookupName = null;
			if (refType.getEnclosingType() == null) {
				lookupName = refType.getQualifiedName();
			} else {
				lookupName = refType.getQualifiedName().replace('$', '.');
			}
			IType type = project.findType(lookupName);
			if (type == null) {
				return;
			}
			ICompilationUnit compilationUnit = type.getCompilationUnit();
			if (compilationUnit == null) {
				return;
			}
			IResource correspondingResource = compilationUnit.getCorrespondingResource();
			if (correspondingResource == null) {
				return;
			}
			IMarker marker = correspondingResource.createMarker(ApiPlugin.API_USAGE_PROBLEM_MARKER);
			IDocument document = Util.getDocument(compilationUnit);
			// retrieve line number, char start and char end
			int lineNumber = location.getLineNumber();
			int charStart = -1;
			int charEnd = -1;
			if (lineNumber == -1) {
				switch(reference.getReferenceKind()) {
					case ReferenceModifiers.REF_IMPLEMENTS :
					case ReferenceModifiers.REF_EXTENDS : {
							// we report the marker on the type
							ISourceRange range = type.getNameRange();
							charStart = range.getOffset();
							charEnd = charStart + range.getLength();
							try {
								lineNumber = document.getLineOfOffset(charStart);
							} catch (BadLocationException e) {
								// ignore
							}
						}
						break;
					case ReferenceModifiers.REF_OVERRIDE : {
							// report the marker on the method
							IMethodDescriptor methodDesc = (IMethodDescriptor) reference.getTargetLocation().getMember();
							String[] parameterTypes = Signature.getParameterTypes(methodDesc.getSignature());
							for (int i = 0; i < parameterTypes.length; i++) {
								parameterTypes[i] = parameterTypes[i].replace('/', '.');
							}
							IMethod Qmethod = type.getMethod(methodDesc.getName(), parameterTypes);
							IMethod[] methods = type.getMethods();
							IMethod match = null;
							for (int i = 0; i < methods.length; i++) {
								IMethod method = methods[i];
								if (method.isSimilar(Qmethod)) {
									match = method;
									break;
								}
							}
							if (match != null) {
								ISourceRange range = match.getNameRange();
								charStart = range.getOffset();
								charEnd = charStart + range.getLength();
								try {
									lineNumber = document.getLineOfOffset(charStart);
								} catch (BadLocationException e) {
									// ignore
								}
							}
						}
						break;
					case ReferenceModifiers.REF_INSTANTIATE : {
						// TODO:
						break;
					}
				}
			}
			IJavaElement element = compilationUnit;
			if(charStart > -1) {
				element = compilationUnit.getElementAt(charStart);
			}
			marker.setAttributes(
				new String[] {
						IMarker.MESSAGE,
						IMarker.SEVERITY,
						IMarker.SOURCE_ID,
						IMarker.LINE_NUMBER,
						IMarker.CHAR_START,
						IMarker.CHAR_END,
						IApiMarkerConstants.MARKER_ATTR_KIND,
						IApiMarkerConstants.MARKER_ATTR_FLAGS,
						IApiMarkerConstants.MARKER_ATT_HANDLE_ID
				},
				new Object[] {
						message,
						new Integer(severity),
						SOURCE,
						new Integer(lineNumber),
						new Integer(charStart),
						new Integer(charEnd),
						new Integer(reference.getReferenceKind()),
						new Integer(REF_TYPE_FLAG),
						(element == null ? compilationUnit.getHandleIdentifier() : element.getHandleIdentifier())
				}
			);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
	}	

	/**
	 * Compares the two given profiles and generates an {@link IDelta}
	 * @param reference
	 * @param component
	 */
	private void compareProfiles(IApiComponent reference, IApiComponent component) {
		long time = System.currentTimeMillis();
		IDelta delta = null;
		if (reference == null) {
			delta = new Delta(IDelta.API_PROFILE_ELEMENT_TYPE, IDelta.ADDED, IDelta.API_COMPONENT, null, component.getId(), null);
		} else {
			try {
				delta = ApiComparator.compare(reference, component, VisibilityModifiers.API);
			} catch(Exception e) {
				ApiPlugin.log(e);
			} finally {
				if (DEBUG) System.out.println("Time spent for " + component.getId() + " : " + (System.currentTimeMillis() - time) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		}
		if (delta == null) {
			if (DEBUG) System.err.println("An error occured while comparing"); //$NON-NLS-1$
			return;
		}
		if (delta != ApiComparator.NO_DELTA) {
			List allDeltas = Util.collectAllDeltas(delta);
			if (allDeltas.size() != 0) {
				IJavaProject javaProject = JavaCore.create(this.fCurrentProject);
				if (javaProject == null) return;
				for (Iterator iterator = allDeltas.iterator(); iterator.hasNext();) {
					IDelta localDelta = (IDelta) iterator.next();
					IType type = null;
					try {
						type = javaProject.findType(localDelta.getTypeName().replace('$', '.'));
					} catch (JavaModelException e) {
						ApiPlugin.log(e);
					}
					if (type == null) {
						// delta reported against an api component or an api profile
						if (!DeltaProcessor.isBinaryCompatible(localDelta)) {
							createMarkerFor(localDelta, null, javaProject, reference, component);
						}
					} else {
						ICompilationUnit compilationUnit = type.getCompilationUnit();
						if (compilationUnit == null) continue;
						processDelta(javaProject, localDelta, compilationUnit, reference, component);
					}
				}
				checkApiComponentVersion(javaProject, reference, component);
			}
			if (DEBUG) System.out.println("Complete"); //$NON-NLS-1$
		} else if (DEBUG) {
			System.out.println("No delta"); //$NON-NLS-1$
		}
	}

	private void processDelta(
			IJavaProject javaProject,
			IDelta delta,
			ICompilationUnit compilationUnit,
			IApiComponent reference,
			IApiComponent component) {
		
		if (DeltaProcessor.isBinaryCompatible(delta)) {
			if (DEBUG) {
				String deltaDetails = "Delta : " + Util.getDetail(delta); //$NON-NLS-1$
				System.out.println(deltaDetails + " is binary compatible"); //$NON-NLS-1$
			}
			switch(delta.getKind()) {
				case IDelta.ADDED :
				case IDelta.ADDED_EXTEND_RESTRICTION :
				case IDelta.ADDED_IMPLEMENT_RESTRICTION :
					// check new apis
					this.bits |= CONTAINS_API_CHANGES;
					int missingTagSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_MISSING_SINCE_TAGS, javaProject);
					int malformedTagSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_MALFORMED_SINCE_TAGS, javaProject);
					int invalidTagVersionSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_INVALID_SINCE_TAG_VERSION, javaProject);
					if (missingTagSeverityLevel != ApiPlugin.SEVERITY_IGNORE
							|| malformedTagSeverityLevel != ApiPlugin.SEVERITY_IGNORE
							|| invalidTagVersionSeverityLevel != ApiPlugin.SEVERITY_IGNORE) {
						// ensure that there is a @since tag for the corresponding member
						IMember member = Util.getIMember(delta, javaProject);
						if (member != null) {
							processMember(
								javaProject,
								compilationUnit,
								member,
								component,
								missingTagSeverityLevel,
								malformedTagSeverityLevel,
								invalidTagVersionSeverityLevel);
						}
					}
			}
		} else {
			if (DEBUG) {
				String deltaDetails = "Delta : " + Util.getDetail(delta); //$NON-NLS-1$
				System.err.println(deltaDetails + " is not binary compatible"); //$NON-NLS-1$
			}
			createMarkerFor(delta, compilationUnit, javaProject, reference, component);
			int missingTagSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_MISSING_SINCE_TAGS, javaProject);
			int malformedTagSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_MALFORMED_SINCE_TAGS, javaProject);
			int invalidTagVersionSeverityLevel = ApiPlugin.getDefault().getSeverityLevel(IApiPreferenceConstants.REPORT_INVALID_SINCE_TAG_VERSION, javaProject);
			if (missingTagSeverityLevel != ApiPlugin.SEVERITY_IGNORE
					|| malformedTagSeverityLevel != ApiPlugin.SEVERITY_IGNORE
					|| invalidTagVersionSeverityLevel != ApiPlugin.SEVERITY_IGNORE) {
				// ensure that there is a @since tag for the corresponding member
				switch(delta.getKind()) {
					case IDelta.ADDED_NOT_IMPLEMENT_RESTRICTION :
					case IDelta.ADDED_NOT_EXTEND_RESTRICTION :
					case IDelta.ADDED_NOT_EXTEND_RESTRICTION_STATIC :
					case IDelta.ADDED :
						IMember member = Util.getIMember(delta, javaProject);
						if (member != null) {
							processMember(
									javaProject,
									compilationUnit,
									member,
									component,
									missingTagSeverityLevel,
									malformedTagSeverityLevel,
									invalidTagVersionSeverityLevel);
						}
						
				}
			}
		}
	}
	
	private void processMember(
			final IJavaProject javaProject,
			final ICompilationUnit compilationUnit,
			final IMember member,
			final IApiComponent component,
			int missingTagSeverityLevel,
			int malformedTagSeverityLevel,
			int invalidTagVersionSeverityLevel) {
		if(compilationUnit != null) {
			ASTParser parser = ASTParser.newParser(AST.JLS3);
			parser.setSource(compilationUnit);
			ISourceRange nameRange = null;
			try {
				nameRange = member.getNameRange();
			} catch (JavaModelException e) {
				ApiPlugin.log(e);
				return;
			}
			if (nameRange == null) return;
			int offset = nameRange.getOffset();
			parser.setFocalPosition(offset);
			parser.setResolveBindings(false);
			Map options = javaProject.getOptions(true);
			options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
			parser.setCompilerOptions(options);
			final CompilationUnit unit = (CompilationUnit) parser.createAST(new NullProgressMonitor());
			SinceTagChecker visitor = new SinceTagChecker(offset);
			unit.accept(visitor);
			if (visitor.hasNoComment() || visitor.isMissing()) {
				StringBuffer buffer = new StringBuffer();
				Version version = null;
				try {
					version = new Version(component.getVersion());
					buffer.append(version.getMajor()).append('.').append(version.getMinor());
					createSinceTagMarker(
						IApiMarkerConstants.MARKER_ATTR_SINCE_TAG_MISSING,
						BuilderMessages.VersionManagementMissingSinceTag,
						compilationUnit,
						member,
						missingTagSeverityLevel,
						String.valueOf(buffer));
				} catch (IllegalArgumentException e) {
					ApiPlugin.log(e);
				}
			} else if (visitor.hasJavadocComment()) {
				// we don't want to flag block comment
				String sinceVersion = visitor.getSinceVersion();
				if (sinceVersion != null) {
					/*
					 * Check the validity of the @since version
					 * It cannot be greater than the component version and
					 * it cannot contain more than two fragments.
					 */
					if (Util.getFragmentNumber(sinceVersion) > 2) {
						// @since version cannot have more than 2 fragments
						// create a marker on the member for missing @since tag
						try {
							SinceTagVersion tagVersion = null;
							tagVersion = new SinceTagVersion(sinceVersion);
							StringBuffer buffer = new StringBuffer();
							buffer.append(' ');
							if (tagVersion.pluginName() != null) {
								buffer.append(tagVersion.pluginName()).append(' ');
							}
							Version version = new Version(component.getVersion());
							if (Util.isGreatherVersion(sinceVersion, component.getVersion())) {
								// report invalid version number
								buffer.append(version.getMajor()).append('.').append(version.getMinor());
							} else {
								buffer.append(version.getMajor()).append('.').append(version.getMinor());
							}
							createSinceTagMarker(
									IApiMarkerConstants.MARKER_ATTR_SINCE_TAG_MALFORMED,
									NLS.bind(
											BuilderMessages.VersionManagementMalformedSinceTag,
											sinceVersion),
											compilationUnit,
											member,
											malformedTagSeverityLevel,
											String.valueOf(buffer));
						} catch (IllegalArgumentException e) {
							ApiPlugin.log(e);
						}
					} else if (Util.isGreatherVersion(sinceVersion, component.getVersion())) {
						// report invalid version number
						SinceTagVersion tagVersion = null;
						try {
							tagVersion = new SinceTagVersion(sinceVersion);
							StringBuffer buffer = new StringBuffer();
							buffer.append(' ');
							if (tagVersion.pluginName() != null) {
								buffer.append(tagVersion.pluginName()).append(' ');
							}
							Version version = new Version(component.getVersion());
							buffer.append(version.getMajor()).append('.').append(version.getMinor());
							createSinceTagMarker(
								IApiMarkerConstants.MARKER_ATTR_SINCE_TAG_INVALID,
								NLS.bind(
									BuilderMessages.VersionManagementSinceTagGreaterThanComponentVersion,
									sinceVersion,
									component.getVersion()),
								compilationUnit,
								member,
								invalidTagVersionSeverityLevel,
								String.valueOf(buffer));
						} catch (IllegalArgumentException e) {
							ApiPlugin.log(e);
						}
					}
				}
			}
		}
	}

	private void createSinceTagMarker(
			final String attributeValue,
			final String message,
			final ICompilationUnit compilationUnit,
			final IMember member,
			int markerSeverity,
			final String version) {
		try {
			// create a marker on the member for missing @since tag
			IResource correspondingResource = null;
			try {
				correspondingResource = compilationUnit.getCorrespondingResource();
			} catch (JavaModelException e) {
				// ignore
			}
			if (correspondingResource == null) return;
			IMarker marker = correspondingResource.createMarker(ApiPlugin.SINCE_TAGS_PROBLEM_MARKER);
			int lineNumber = 1;
			int charStart = 0;
			int charEnd = 1;
			ISourceRange range = member.getNameRange();
			charStart = range.getOffset();
			charEnd = charStart + range.getLength();
			try {
				IDocument document = Util.getDocument(compilationUnit);
				lineNumber = document.getLineOfOffset(charStart);
			} catch (BadLocationException e) {
				// ignore
			}
			marker.setAttributes(
				new String[] {
						IMarker.MESSAGE,
						IMarker.SEVERITY,
						IMarker.SOURCE_ID,
						IMarker.LINE_NUMBER,
						IMarker.CHAR_START,
						IMarker.CHAR_END,
						IApiMarkerConstants.MARKER_ATTR_KIND,
						IApiMarkerConstants.MARKER_ATTR_VERSION,
				},
				new Object[] {
						message,
						new Integer(markerSeverity),
						SOURCE,
						new Integer(lineNumber),
						new Integer(charStart),
						new Integer(charEnd),
						attributeValue,
						version
				}
			);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
	}

	private void createVersionNumberingProblemMarkerMarker(
			final String message,
			int markerSeverity,
			boolean breakage,
			String version) {
		try {
			IMarker[] markers = this.fCurrentProject.findMarkers(ApiPlugin.BINARY_COMPATIBILITY_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
			IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
			if (manifestFile == null) {
				// Cannot retrieve the manifest.mf file
				return;
			}
			IMarker[] manifestMarkers = manifestFile.findMarkers(ApiPlugin.VERSION_NUMBERING_PROBLEM_MARKER, false, IResource.DEPTH_ZERO);
			if (markers.length != 0) {
				// check if we already have such a version numbering marker
				if (manifestMarkers.length != 0) {
					// check if the existing marker has the same severity (breakage vs non breakage
					IMarker marker = manifestMarkers[0];
					Object attribute = marker.getAttribute(IApiMarkerConstants.MARKER_ATTR_KIND);
					if (breakage) {
						if (IApiMarkerConstants.MARKER_ATTR_MAJOR_VERSION_CHANGE.equals(attribute)) {
							// no need to create the same marker again
							return;
						} else {
							// create a new marker
							marker.delete();
						}
					}
					// no need to report a marker for minor version issue if the major version is wrong 
					// or to report the minor issue again. We preserve the existing marker
					return;
				}
			} else if (manifestMarkers.length != 0) {
				// this means the marker is not create for a breakage change
				IMarker marker = manifestMarkers[0];
				Object attribute = marker.getAttribute(IApiMarkerConstants.MARKER_ATTR_KIND);
				if (IApiMarkerConstants.MARKER_ATTR_MAJOR_VERSION_CHANGE.equals(attribute)) {
					// remove the existing marker to create one for non breakage version issue
					marker.delete();
				}
				// no need to report a marker for minor version issue again
				return;
			}
			// this error should be located on the manifest.mf file
			// first of all we check how many binary breakage marker are there
			IMarker marker = manifestFile.createMarker(ApiPlugin.VERSION_NUMBERING_PROBLEM_MARKER);
			marker.setAttributes(
				new String[] {
						IMarker.MESSAGE,
						IMarker.SEVERITY,
						IMarker.SOURCE_ID,
						IApiMarkerConstants.MARKER_ATTR_KIND,
						IApiMarkerConstants.MARKER_ATTR_VERSION,
				},
				new Object[] {
						message,
						new Integer(markerSeverity),
						SOURCE,
						breakage ? IApiMarkerConstants.MARKER_ATTR_MAJOR_VERSION_CHANGE : IApiMarkerConstants.MARKER_ATTR_MINOR_VERSION_CHANGE,
						version
				}
			);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
	}
}
