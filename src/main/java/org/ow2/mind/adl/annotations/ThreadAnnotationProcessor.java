/**
 * Copyright (C) 2014 Schneider-Electric
 *
 * This file is part of "Mind Compiler" is free software: you can redistribute 
 * it and/or modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: mind@ow2.org
 *
 * Authors: Julien Tous
 * Contributors: 
 */
package org.ow2.mind.adl.annotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.antlr.stringtemplate.StringTemplate;
import org.objectweb.fractal.adl.ADLException;
import org.objectweb.fractal.adl.Definition;
import org.objectweb.fractal.adl.Node;
import org.objectweb.fractal.adl.NodeFactory;
import org.objectweb.fractal.adl.interfaces.Interface;
import org.objectweb.fractal.adl.interfaces.InterfaceContainer;
import org.objectweb.fractal.adl.types.TypeInterface;
import org.ow2.mind.SourceFileWriter;
import org.ow2.mind.adl.annotation.ADLLoaderPhase;
import org.ow2.mind.adl.annotation.AbstractADLLoaderAnnotationProcessor;
import org.ow2.mind.adl.ast.ASTHelper;
import org.ow2.mind.adl.ast.Binding;
import org.ow2.mind.adl.ast.BindingContainer;
import org.ow2.mind.adl.ast.Component;
import org.ow2.mind.adl.ast.ComponentContainer;
import org.ow2.mind.adl.ast.Data;
import org.ow2.mind.adl.ast.DefinitionReference;
import org.ow2.mind.adl.ast.ImplementationContainer;
import org.ow2.mind.adl.ast.MindInterface;
import org.ow2.mind.adl.ast.Source;
import org.ow2.mind.adl.idl.InterfaceDefinitionDecorationHelper;
import org.ow2.mind.annotation.Annotation;
import org.ow2.mind.annotation.AnnotationHelper;
import org.ow2.mind.idl.IDLLocator;
import org.ow2.mind.idl.ast.IDL;
import org.ow2.mind.idl.ast.InterfaceDefinition;
import org.ow2.mind.io.OutputFileLocator;

import com.google.inject.Inject;

/**
 * @author Julien TOUS
 */
public class ThreadAnnotationProcessor
extends
AbstractADLLoaderAnnotationProcessor {

	@Inject
	protected OutputFileLocator   outFileLocator;

	@Inject
	protected IDLLocator   idlLocator;

	@Inject
	protected NodeFactory         nodeFactory;

	//String template that holds the declaration of the C symbols 
	protected static final String THREAD_DATA_TEMPLATE_NAME  = "st.THREADDATA";
	//String template that holds the source code of the to be created component
	protected static final String THREAD_SOURCE_TEMPLATE_NAME = "st.THREADSOURCE";

	/**
	 *	Create a  component in front of the annotated component 
	 */
	public Definition processAnnotation(final Annotation annotation,
			final Node node, final Definition upperCompDef, final ADLLoaderPhase phase,
			final Map<Object, Object> context) throws ADLException {
		assert annotation instanceof Thread;
		if (node instanceof Component){
			final Component threadedComp = (Component) node;
			final Definition threadedCompDef = ASTHelper.getResolvedComponentDefinition(threadedComp, loaderItf, context);	
			final String srvInterceptorCompName = threadedComp.getName() + "_thread";
			
			//Creating the srv interceptor definition
			DefinitionReference srvInterceptorDefRef = ASTHelper.newDefinitionReference(nodeFactory, srvInterceptorCompName);
			Definition srvInterceptorDef = ASTHelper.newPrimitiveDefinitionNode(nodeFactory, srvInterceptorCompName, srvInterceptorDefRef);
			ASTHelper.setResolvedDefinition(srvInterceptorDefRef, srvInterceptorDef);
		
			Interface[] itfs  = ((InterfaceContainer)threadedCompDef).getInterfaces(); 
			
			Map<String,InterfaceDefinition> srvs = new HashMap<String,InterfaceDefinition>();
			if (itfs != null) {
				for (Interface itf : itfs) {
					if (((TypeInterface)itf).getRole().equals(TypeInterface.SERVER_ROLE)){
						final InterfaceDefinition itfDef = itfSignatureResolverItf.resolve((TypeInterface) itf, upperCompDef, context);
						//Creating a srv interface with the same signature
						MindInterface srvItf = ASTHelper.newServerInterfaceNode(nodeFactory, itf.getName() , itfDef.getName());
						final TypeInterface interceptorSrvType = srvItf;
						InterfaceDefinitionDecorationHelper.setResolvedInterfaceDefinition(interceptorSrvType, itfDef);
						((InterfaceContainer) srvInterceptorDef).addInterface(srvItf);
						srvs.put(srvItf.getName(),itfDef);
						//Creating a clt interface with the same signature						
						MindInterface cltItf = ASTHelper.newClientInterfaceNode(nodeFactory, itf.getName() + "_threaded" , itfDef.getName());
						final TypeInterface interceptorCltType = cltItf;
						InterfaceDefinitionDecorationHelper.setResolvedInterfaceDefinition(interceptorCltType, itfDef);
						((InterfaceContainer) srvInterceptorDef).addInterface(cltItf);

					} 
				}
			}
			
			//Creating private data for our interceptor
			final StringBuilder dataCode = new StringBuilder();
			final StringTemplate dataST = getTemplate(THREAD_DATA_TEMPLATE_NAME,"PrivateDataDeclaration");
			dataST.setAttribute("component", threadedCompDef);
			dataST.setAttribute("interfaces", srvs);
			dataCode.append(dataST.toString());
			//Adding the data to the definition of our interceptor-component
			final Data data = ASTHelper.newData(nodeFactory);
			((ImplementationContainer) srvInterceptorDef).setData(data);
			data.setCCode(dataCode.toString());

			//Creating the source code for our interceptor-component
			final StringBuilder sourceCode = new StringBuilder();
			final StringTemplate sourceST = getTemplate(THREAD_SOURCE_TEMPLATE_NAME, "InterceptedServerDefinition");
			sourceST.setAttribute("component", threadedCompDef);
			sourceST.setAttribute("interfaces", srvs);
			

			
			//sourceST.setAttribute("componentDefinition", srvInterceptorDef);
			sourceCode.append(sourceST.toString());
			//Adding the source to the definition of our interceptor-component
			final Source src = ASTHelper.newSource(nodeFactory);
			((ImplementationContainer) srvInterceptorDef).addSource(src);
			src.setCCode(sourceCode.toString());	
		
			//Instantiating and adding a the interceptor-component 
			final Component srvInterceptorComp = ASTHelper.newComponent(nodeFactory, srvInterceptorCompName, srvInterceptorDefRef);
			ASTHelper.setResolvedComponentDefinition(srvInterceptorComp, srvInterceptorDef);
			((ComponentContainer) upperCompDef).addComponent(srvInterceptorComp);
			
			Binding[] bindings = ((BindingContainer) upperCompDef).getBindings();
			for (Binding binding : bindings) {
				if (binding.getToComponent().equals(threadedComp.getName())){
					//Re-routing existing binding
					binding.setToComponent(srvInterceptorComp.getName());
					//Creating binding from interceptor to comp
					final Binding interceptorBinding = ASTHelper.newBinding(nodeFactory);
					interceptorBinding.setFromComponent(srvInterceptorComp.getName());
					interceptorBinding.setToComponent(threadedComp.getName());
					interceptorBinding.setFromInterface(binding.getToInterface() + "_threaded");
					interceptorBinding.setToInterface(binding.getToInterface());			
					((BindingContainer) upperCompDef).addBinding(interceptorBinding);
				}
			}
		}
		return null;
	}

}