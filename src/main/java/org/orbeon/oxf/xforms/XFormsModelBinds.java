/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.orbeon.errorified.Exceptions;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.XPath;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xforms.analysis.XPathDependencies;
import org.orbeon.oxf.xforms.analysis.model.BindTree;
import org.orbeon.oxf.xforms.analysis.model.Model;
import org.orbeon.oxf.xforms.event.Dispatch;
import org.orbeon.oxf.xforms.event.events.XXFormsXPathErrorEvent;
import org.orbeon.oxf.xforms.function.XFormsFunction;
import org.orbeon.oxf.xforms.model.DataModel;
import org.orbeon.oxf.xml.NamespaceMapping;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.saxon.dom4j.TypedNodeWrapper;
import org.orbeon.saxon.expr.XPathContext;
import org.orbeon.saxon.expr.XPathContextMajor;
import org.orbeon.saxon.om.*;
import org.orbeon.saxon.sxpath.IndependentContext;
import org.orbeon.saxon.sxpath.XPathEvaluator;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.type.BuiltInAtomicType;
import org.orbeon.saxon.type.BuiltInType;
import org.orbeon.saxon.type.ConversionResult;
import org.orbeon.saxon.type.ValidationFailure;
import org.orbeon.saxon.value.BooleanValue;
import org.orbeon.saxon.value.QNameValue;
import org.orbeon.saxon.value.SequenceExtent;
import org.orbeon.saxon.value.StringValue;
import org.orbeon.scaxon.XML;
import org.w3c.dom.Node;

import java.util.*;

/**
 * Represent a given model's binds.
 */
public class XFormsModelBinds {
    
    private final XFormsModel model;                            // model to which we belong
    private final Model staticModel;

    private final IndentedLogger indentedLogger;
    private final XFormsContainingDocument containingDocument;  // current containing document
    private final XPathDependencies dependencies;

    private List<Bind> topLevelBinds = new ArrayList<Bind>();
    private Map<String, Bind> singleNodeContextBinds = new HashMap<String, Bind>();
    private Map<Item, List<Bind.BindIteration>> iterationsForContextNodeInfo = new HashMap<Item, List<Bind.BindIteration>>();

    private XFormsModelSchemaValidator xformsValidator;         // validator for standard XForms schema types

    private boolean isFirstCalculate;                           // whether this is the first recalculate for the associated XForms model
    private boolean isFirstRebuild;                             // whether this is the first rebuild for the associated XForms model

    /**
     * Create an instance of XFormsModelBinds if the given model has xf:bind elements.
     *
     * @param model XFormsModel
     * @return      XFormsModelBinds or null if the model doesn't have xf:bind elements
     */
    public static XFormsModelBinds create(XFormsModel model) {
        return model.getStaticModel().hasBinds() ? new XFormsModelBinds(model) : null;
    }

    private XFormsModelBinds(XFormsModel model) {
        this.model = model;

        this.indentedLogger = model.getIndentedLogger();
        this.containingDocument = model.containingDocument;
        this.dependencies = this.containingDocument.getXPathDependencies();

        this.staticModel = model.getStaticModel();

        // For the lifecycle of an XForms document, new XFormsModelBinds() may be created multiple times, e.g. if the
        // state is deserialized, but we know that new XFormsModelBinds() will occur only once during document
        // initialization. So the assignation below is ok.
        this.isFirstCalculate = this.isFirstRebuild = containingDocument.isInitializing();
    }

    public void resetFirstCalculate() {
        this.isFirstCalculate = true;
    }

    /**
     * Rebuild all binds, computing all bind nodesets (but not computing the MIPs)
     */
    public void rebuild() {

        if (indentedLogger.isDebugEnabled())
            indentedLogger.startHandleOperation("model", "performing rebuild", "model id", model.getEffectiveId());

        // Reset everything
        // NOTE: Assume that model.getContextStack().resetBindingContext(model) was called
        topLevelBinds.clear();
        singleNodeContextBinds.clear();
        iterationsForContextNodeInfo.clear();

        // Clear all instances that might have InstanceData
        // Only need to do this after the first rebuild
        if (! isFirstRebuild) {
            for (final XFormsInstance instance : model.getInstances()) {
                // Only clear instances that are impacted by xf:bind/(@ref|@nodeset), assuming we were able to figure out the dependencies
                // The reason is that clearing this state can take quite some time
                final boolean instanceMightBeSchemaValidated = model.hasSchema() && instance.isSchemaValidation();
                final boolean instanceMightHaveMips =
                        dependencies.hasAnyCalculationBind(staticModel, instance.getPrefixedId()) ||
                        dependencies.hasAnyValidationBind(staticModel, instance.getPrefixedId());

                if (instanceMightBeSchemaValidated || instanceMightHaveMips) {
                    DataModel.visitElementJava(instance.rootElement(), new DataModel.NodeVisitor() {
                        public void visit(NodeInfo nodeInfo) {
                            InstanceData.clearState(nodeInfo);
                        }
                    });
                }
            }
        }

        // Iterate through all top-level bind elements to create new bind tree
        // TODO: In the future, XPath dependencies must allow for partial rebuild of the tree as is the case with controls
        // Even before that, the bind tree could be modified more dynamically as is the case with controls
        for (final BindTree.Bind staticBind : staticModel.topLevelBindsJava())
            topLevelBinds.add(new Bind(staticBind, true)); // remember as top-level bind

        isFirstRebuild = false;

        if (indentedLogger.isDebugEnabled())
            indentedLogger.endHandleOperation();
    }

    /**
     * Apply calculate binds.
     *
     * @param applyDefaults    whether to apply initial values (@xxf:default="...")
     */
    public void applyCalculateBinds(boolean applyDefaults) {

        if (!staticModel.hasCalculateComputedCustomBind()) {
            // We can skip this
            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("model", "skipping bind recalculate", "model id", model.getEffectiveId(), "reason", "no recalculation binds");
        } else {
            // This model may have calculation binds

            if (indentedLogger.isDebugEnabled())
                indentedLogger.startHandleOperation("model", "performing bind recalculate", "model id", model.getEffectiveId());
            {
                // 1. Evaluate initial values and calculate before the rest

                if (isFirstCalculate || applyDefaults) {
                    // Handle default values first
                    if (staticModel.hasDefaultValueBind())
                        iterateBinds(new BindRunner() {
                            public void applyBind(Bind bind, int position) {
                                if (bind.staticBind.getDefaultValue() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.DEFAULT()))
                                    handleDefaultValueBind(bind, position);
                            }
                        });
                    // This will be false from now on as we have done our first handling of calculate binds
                    isFirstCalculate = false;
                }

                // Handle calculations
                if (staticModel.hasCalculateBind())
                    iterateBinds(new BindRunner() {
                        public void applyBind(Bind bind, int position) {
                            if (bind.staticBind.getCalculate() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.CALCULATE()))
                                handleCalculateBind(bind, position);
                        }
                    });

                // 2. Update computed expression binds if requested
                applyComputedExpressionBinds();
            }

            if (indentedLogger.isDebugEnabled())
                indentedLogger.endHandleOperation();
        }
    }

    /**
     * Apply required, relevant and readonly binds.
     *
     */
    public void applyComputedExpressionBinds() {

        // Reset context stack just to re-evaluate the variables as instance values may have changed with @calculate
        model.resetAndEvaluateVariables();

        // Apply
        iterateBinds(new BindRunner() {
            public void applyBind(Bind bind, int position) {
                if (bind.staticBind.hasCalculateComputedMIPs() || bind.staticBind.hasCustomMIPs()) // don't bother if not
                    handleComputedExpressionBind(bind, position);
            }
        });
    }

    /**
     * Apply validation binds
     *
     * @param invalidInstances set filled with invalid instances if any
     */
    public void applyValidationBinds(final Set<String> invalidInstances) {

        if (!staticModel.hasValidateBind()) {
            // We can skip this
            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("model", "skipping bind revalidate", "model id", model.getEffectiveId(), "reason", "no validation binds");
        } else {
            // This model may have validation binds

            // Reset context stack just to re-evaluate the variables
            model.resetAndEvaluateVariables();

            // 1. Validate based on type and requiredness
            if (staticModel.hasTypeBind() || staticModel.hasRequiredBind())
                iterateBinds(new BindRunner() {
                    public void applyBind(Bind bind, int position) {
                        if (bind.staticBind.getType() != null || bind.staticBind.getRequired() != null) // don't bother if not
                            validateTypeAndRequired(bind, position, invalidInstances);
                    }
                });

            // 2. Validate constraints
            if (staticModel.hasConstraintBind())
                iterateBinds(new BindRunner() {
                    public void applyBind(Bind bind, int position) {
                        if (bind.staticBind.getConstraint() != null) // don't bother if not
                            validateConstraint(bind, position, invalidInstances);
                    }
                });
        }
    }

    /**
     * Return the nodeset for a given bind and context item, as per "4.7.2 References to Elements within a bind
     * Element".
     *
     * @param bindId        id of the bind to handle
     * @param contextItem   context item if necessary
     * @return              bind nodeset
     */
    public List<Item> getBindNodeset(String bindId, Item contextItem) {

        final Bind bind = resolveBind(bindId, contextItem);
        return (bind != null) ? bind.nodeset : XFormsConstants.EMPTY_ITEM_LIST;
    }

    public Bind resolveBind(String bindId, Item contextItem) {

        final Bind singleNodeContextBind = singleNodeContextBinds.get(bindId);
        if (singleNodeContextBind != null) {
            // This bind has a single-node context (incl. top-level bind), so ignore context item and just return the bind nodeset
            return singleNodeContextBind;
        } else {
            // Nested bind, context item will be used

            // This requires a context node, not just any item
            if (contextItem instanceof NodeInfo) {
                final List<Bind.BindIteration> iterationsForContextNode = iterationsForContextNodeInfo.get(contextItem);
                if (iterationsForContextNode != null) {
                    for (final Bind.BindIteration currentIteration: iterationsForContextNode) {
                        final Bind currentBind = currentIteration.getBind(bindId);
                        if (currentBind != null) {
                            // Found
                            return currentBind;
                        }
                    }
                }
            }
            // "From among the bind objects associated with the target bind element, if there exists a bind object
            // created with the same in-scope evaluation context node as the source object, then that bind object is the
            // desired target bind object. Otherwise, the IDREF resolution produced a null search result."
        }

        // Nothing found
        return null;
    }

    public Item evaluateBindByType(Bind bind, int position, QName mipType) throws XPathException {

        if (mipType.equals(XFormsConstants.RELEVANT_QNAME)) {
            // Relevant
            final Boolean relevant = evaluateRelevantMIP(bind, position);
            return (relevant != null) ? BooleanValue.get(relevant) : null;
        } else if (mipType.equals(XFormsConstants.READONLY_QNAME)) {
            // Readonly
            final Boolean readonly = evaluateReadonlyMIP(bind, position);
            return (readonly != null) ? BooleanValue.get(readonly) : null;
        } else if (mipType.equals(XFormsConstants.REQUIRED_QNAME)) {
            // Required
            final Boolean required = evaluateRequiredMIP(bind, position);
            return (required != null) ? BooleanValue.get(required) : null;
        } else if (mipType.equals(XFormsConstants.TYPE_QNAME)) {
            // Type
            final NamespaceMapping namespaceMapping = bind.staticBind.namespaceMapping();
            final QName type = bind.evaluateTypeQName(namespaceMapping.mapping);
            return (type != null) ? new QNameValue(type.getNamespacePrefix(), type.getNamespaceURI(), type.getName(), null) : null;
        } else if (mipType.equals(XFormsConstants.CONSTRAINT_QNAME)) {
            // Constraint
            final Boolean constraint = evaluateConstraintMIP(bind, position);
            return (constraint != null) ? BooleanValue.get(constraint) : null;
        } else if (mipType.equals(XFormsConstants.CALCULATE_QNAME)) {
            // Calculate
            final String result = evaluateCalculateBind(bind, position);
            return (result != null) ? new StringValue(result) : null;
        } else if (mipType.equals(XFormsConstants.XXFORMS_DEFAULT_QNAME)) {
            // xxf:default
            final String result = evaluateXXFormsDefaultBind(bind, position);
            return (result != null) ? new StringValue(result) : null;
        } else {
            // Try custom MIPs
            final String result = evaluateCustomMIP(bind, Model.buildCustomMIPName(mipType.getQualifiedName()), position);
            return (result != null) ? new StringValue(result) : null;
        }
    }

    // Iterate over all binds and for each one do the callback.
    private void iterateBinds(BindRunner bindRunner) {
        // Iterate over top-level binds
        for (final Bind currentBind : topLevelBinds) {
            try {
                currentBind.applyBinds(bindRunner);
            } catch (Exception e) {
                throw ValidationException.wrapException(e, new ExtendedLocationData(currentBind.staticBind.locationData(), "evaluating XForms binds", currentBind.staticBind.element()));
            }
        }
    }

    private void handleMIPXPathException(Exception e, Bind bind, BindTree.Bind.XPathMIP xpathMIP, String message) {

        final Throwable root = Exceptions.getRootThrowable(e);
        if (root instanceof TypedNodeWrapper.TypedValueException) {
            // Consider validation errors as ignorable. The rationale is that if the function (the XPath
            // expression) works on inputs that are not valid (hence the validation error), then the function cannot
            // produce a meaningful result. We think that it is worth handling this condition slightly differently from
            // other dynamic and static errors, so that users can just write expression without constant checks with
            // `castable as` or `instance of`.
            final TypedNodeWrapper.TypedValueException typeValueException = (TypedNodeWrapper.TypedValueException) root;
            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("", "typed value exception",
                        "node name",     typeValueException.nodeName,
                        "expected type", typeValueException.typeName,
                        "actual value",  typeValueException.nodeValue);
        } else {
            // All other errors dispatch an event and will cause the usual fatal-or-not behavior
            final ValidationException ve = ValidationException.wrapException(e, new ExtendedLocationData(bind.staticBind.locationData(), message,
                bind.staticBind.element(), "expression", xpathMIP.compiledExpression().string()));
            Dispatch.dispatchEvent(new XXFormsXPathErrorEvent(model, ve.getMessage(), ve));
        }
    }

    private String evaluateXXFormsDefaultBind(Bind bind, int position) {
        // Handle xxf:default MIP
        if (bind.staticBind.getDefaultValue() != null) {
            // Compute default value
            try {
                return evaluateStringExpression(bind.nodeset, position, bind.staticBind.getDefaultValue());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getDefaultValue(), "evaluating XForms default bind");
                return null;
            }
        } else {
            return null;
        }
    }

    private void handleDefaultValueBind(Bind bind, int position) {

        final String stringResult = evaluateXXFormsDefaultBind(bind, position);
        if (stringResult != null) {
            // TODO: Detect if we have already handled this node and handle this error
            final NodeInfo currentNodeInfo = (NodeInfo) bind.nodeset.get(position - 1);
            DataModel.jSetValueIfChanged(containingDocument, indentedLogger, model, bind.staticBind.locationData(), currentNodeInfo, stringResult, "default", true);
        }
    }

    public void handleCalculateBind(Bind bind, int position) {
        final String stringResult = evaluateCalculateBind(bind, position);
        if (stringResult != null) {
            // TODO: Detect if we have already handled this node and handle this error
            final NodeInfo currentNodeInfo = (NodeInfo) bind.nodeset.get(position - 1);
            DataModel.jSetValueIfChanged(containingDocument, indentedLogger, model, bind.staticBind.locationData(), currentNodeInfo, stringResult, "calculate", true);
        }
    }

    public String evaluateCalculateBind(Bind bind, int position) {
        // Handle calculate MIP
        if (bind.staticBind.getCalculate() != null) {
            // Compute calculated value
            try {
                return evaluateStringExpression(bind.nodeset, position, bind.staticBind.getCalculate());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getCalculate(), "evaluating XForms calculate bind");
                // Blank value so we don't have stale calculated values
                return "";
            }
        } else {
            return null;
        }
    }

    private void handleComputedExpressionBind(Bind bind, int position) {

        // Handle relevant, readonly, required, and custom MIPs
        if (bind.staticBind.getRelevant() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.RELEVANT()))
            evaluateAndSetRelevantMIP(bind, position);
        if (bind.staticBind.getReadonly() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.READONLY()) || bind.staticBind.getCalculate() != null)
            evaluateAndSetReadonlyMIP(bind, position);
        if (bind.staticBind.getRequired() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.REQUIRED()))
            evaluateAndSetRequiredMIP(bind, position);

        // TODO: optimize those as well
        evaluateAndSetCustomMIPs(bind, position);
    }

    private void evaluateAndSetCustomMIPs(Bind bind, int position) {
        final Map<String, BindTree.Bind.XPathMIP> customMips = bind.staticBind.customMIPs();
        if (customMips != null && customMips.size() > 0) {
            for (final String propertyName: customMips.keySet()) {
                final String stringResult = evaluateCustomMIP(bind, propertyName, position);
                if (stringResult != null)
                    bind.setCustom(position, propertyName, stringResult);
            }
        }
    }

    private String evaluateCustomMIP(Bind bind, String propertyName, int position) {
        final Map<String, BindTree.Bind.XPathMIP> customMips = bind.staticBind.customMIPs();
        if (customMips != null && customMips.size() > 0) {
            final BindTree.Bind.XPathMIP xPathMIP = customMips.get(propertyName);
            if (xPathMIP != null) {
                try {
                    return evaluateStringExpression(bind.nodeset, position, xPathMIP);
                } catch (Exception e) {
                    handleMIPXPathException(e, bind, bind.staticBind.getCalculate(), "evaluating XForms custom bind");// TODO: "name", propertyName
                    return null;
                }
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    private void evaluateAndSetRequiredMIP(Bind bind, int position) {
        final Boolean required = evaluateRequiredMIP(bind, position);
        if (required != null) {
            // Update node with MIP value
            bind.setRequired(position, required);
        }
    }

    private Boolean evaluateRequiredMIP(Bind bind, int position) {
        if (bind.staticBind.getRequired() != null) {
            // Evaluate "required" XPath expression on this node
            try {
                // Get MIP value
                return evaluateBooleanExpression(bind.nodeset, position, bind.staticBind.getRequired());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getRequired(), "evaluating XForms required bind");
                return ! Model.DEFAULT_REQUIRED(); // https://github.com/orbeon/orbeon-forms/issues/835
            }
        } else {
            return null;
        }
    }

    private void evaluateAndSetReadonlyMIP(Bind bind, int position) {
        final Boolean readonly = evaluateReadonlyMIP(bind, position);
        if (readonly != null) {
            // Mark node
            bind.setReadonly(position, readonly);
        } else if (bind.staticBind.getCalculate() != null) {
            // The bind doesn't have a readonly attribute, but has a calculate: set readonly to true()
            bind.setReadonly(position, true);
        }
    }

    private Boolean evaluateReadonlyMIP(Bind bind, int position) {
        if (bind.staticBind.getReadonly() != null) {
            // The bind has a readonly attribute
            // Evaluate "readonly" XPath expression on this node
            try {
                return evaluateBooleanExpression(bind.nodeset, position, bind.staticBind.getReadonly());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getReadonly(), "evaluating XForms readonly bind");
                return ! Model.DEFAULT_READONLY(); // https://github.com/orbeon/orbeon-forms/issues/835
            }
        } else {
            return null;
        }
    }

    private void evaluateAndSetRelevantMIP(Bind bind, int position) {
        final Boolean relevant = evaluateRelevantMIP(bind, position);
        if (relevant != null) {
            // Mark node
            bind.setRelevant(position, relevant);
        }
    }

    private Boolean evaluateRelevantMIP(Bind bind, int position) {
        if (bind.staticBind.getRelevant() != null) {
            // Evaluate "relevant" XPath expression on this node
            try {
                return evaluateBooleanExpression(bind.nodeset, position, bind.staticBind.getRelevant());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getRelevant(), "evaluating XForms relevant bind");
                return ! Model.DEFAULT_RELEVANT(); // https://github.com/orbeon/orbeon-forms/issues/835
            }
        } else {
            return null;
        }
    }

    private class ModelVariableResolver extends XPath.JVariableResolver {
        public ValueRepresentation apply(StructuredQName variableName, Item contextItem) {
            final BindTree.Bind bind = staticModel.jBindsByName().get(variableName.getLocalName());
            if (bind != null) {
                // Variable value is the bind nodeset
                final List<Item> currentBindNodeset = getBindNodeset(bind.staticId(), contextItem);
                return new SequenceExtent(currentBindNodeset);
            } else {
                // Try top-level model variables
                final Map<String, ValueRepresentation> modelVariables = model.getContextStack().getCurrentVariables();
                final ValueRepresentation result = modelVariables.get(variableName.getLocalName());
                // NOTE: With XPath analysis on, variable scope has been checked statically
                if (result == null)
                    throw new ValidationException("Undeclared variable in XPath expression: $" + variableName.getClarkName(), staticModel.locationData());
                return result;
            }
        }
    }

    private ModelVariableResolver variableResolver = new ModelVariableResolver();

    private String evaluateStringExpression(List<Item> nodeset, int position, BindTree.Bind.XPathMIP xpathExpression) {

        // Setup function context
        // NOTE: When we implement support for allowing binds to receive events, source must be bind id.
        final XFormsFunction.Context functionContext = model.getContextStack().getFunctionContext(model.getEffectiveId());
        final String result;
        try {
            result = XPath.evaluateAsString(
                nodeset,
                position,
                xpathExpression.compiledExpression(),
                functionContext,
                variableResolver,
                containingDocument.getRequestStats().getReporter());
        } finally {
            // Restore function context
            model.getContextStack().returnFunctionContext();
        }

        return result;
    }

    private boolean evaluateBooleanExpression(List<Item> nodeset, int position, BindTree.Bind.XPathMIP xpathExpression) {

        // Setup function context
        // NOTE: When we implement support for allowing binds to receive events, source must be bind id.
        final XFormsFunction.Context functionContext = model.getContextStack().getFunctionContext(model.getEffectiveId());

        final boolean result = (Boolean) XPath.evaluateSingle(
                nodeset,
                position,
                xpathExpression.compiledExpression(),
                functionContext,
                variableResolver,
                containingDocument.getRequestStats().getReporter());

        // Restore function context
        model.getContextStack().returnFunctionContext();

        return result;
    }

    private void validateTypeAndRequired(Bind bind, int position, Set<String> invalidInstances) {

        assert bind.staticBind.getType() != null || bind.staticBind.getRequired() != null;

        // Don't try to apply validity to a node if it has children nodes or if it's not a node
        // "The type model item property is not applied to instance nodes that contain child elements"
        final BindNode bindNode = bind.getBindNode(position);
        final NodeInfo currentNodeInfo = bindNode.nodeInfo;
        if (currentNodeInfo == null || bindNode.hasChildrenElements)
            return;

        // NOTE: 2011-02-03: Decided to also apply this to required validation.
        // See: http://forge.ow2.org/tracker/index.php?func=detail&aid=315821&group_id=168&atid=350207

        // Current required value (computed during previous recalculate)
        final boolean isRequired = InstanceData.getRequired(currentNodeInfo);

        // 1. Check type validity

        // Type MIP @type attribute is special:
        //
        // o it is not an XPath expression
        // o but because type validation can be expensive, we want to optimize that if we can
        // o so requireModelMIPUpdate(Model.TYPE) actually means "do we need to update type validity"
        //
        // xxf:xml and xxf:xpath2 also depend on requiredness, which is probably not a good idea. To handle
        // this condition (partially), if the same bind has @type and @required, we also reevaluate type validity if
        // requiredness has changed. Ideally:
        //
        // o we would not depend on requiredness
        // o but if we did, we should handle also the case where another bind is setting requiredness on the node
        //
        final boolean typeValidity;
        if (bind.typeQName != null) {
             if (dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.TYPE())
                     || bind.staticBind.getRequired() != null && dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.REQUIRED())) {
                 // Compute new type validity if the value of the node might have changed OR the value of requiredness
                 // might have changed
                typeValidity = validateType(bind, currentNodeInfo, isRequired);
                bind.setTypeValidity(position, typeValidity);
             } else {
                 // Keep current value
                typeValidity = bindNode.isTypeValid();
             }
        } else {
            // Keep current value (defaults to true when no type attribute)
            typeValidity = bindNode.isTypeValid();
        }

        // 2. Check required validity
        // We compute required validity every time
        final boolean requiredValidity;
        if (isRequired) {
            // Required
            final String nodeValue = DataModel.getValue(currentNodeInfo);
            requiredValidity = !isEmptyValue(nodeValue); // not valid if value is empty
        } else {
            // Not required, so any value passes including empty as far as required is
            // concerned
            requiredValidity = true;
        }
        bind.setRequiredValidity(position, requiredValidity);

        // Remember invalid instances
        if (!typeValidity || !requiredValidity) {
            final XFormsInstance instanceForNodeInfo = containingDocument.getInstanceForNode(currentNodeInfo);
            if (instanceForNodeInfo != null)
                invalidInstances.add(instanceForNodeInfo.getEffectiveId());
        }
    }

    private void validateConstraint(Bind bind, int position, Set<String> invalidInstances) {

        assert bind.staticBind.getConstraint() != null;

        // Don't try to apply validity to a node if it's not a node
        final BindNode bindNode = bind.getBindNode(position);
        final NodeInfo currentNodeInfo = bindNode.nodeInfo;
        if (currentNodeInfo == null)
            return;

        // NOTE: 2011-02-03: Decided to allow setting a constraint on an element with children. Handles the case of
        // assigning validity to an enclosing element.
        // See: http://forge.ow2.org/tracker/index.php?func=detail&aid=315821&group_id=168&atid=350207

        final boolean typeValidity = InstanceData.getTypeValid(currentNodeInfo); // all type validity has been computed now
        final Boolean constraintValidity;
        if (typeValidity) {
            // Then bother checking @constraint
            if (dependencies.requireModelMIPUpdate(staticModel, bind.staticBind, Model.CONSTRAINT())) {
                // Re-evaluate and set
                constraintValidity = evaluateConstraintMIP(bind, position);
                if (constraintValidity != null)
                    bind.setConstraintValidity(position, constraintValidity);
            } else
                // Keep current value
                constraintValidity = bindNode.isConstraintValidity();
        } else {
            // Type is invalid and we don't want to risk running an XPath expression against an invalid node type
            // This is a common scenario, e.g. <xf:bind type="xs:integer" constraint=". > 0"/>
            // Force constraint to false in this case
            constraintValidity = false;
            bind.setConstraintValidity(position, false);
        }

        // Remember invalid instances
        if (constraintValidity != null && !constraintValidity) {
            final XFormsInstance instanceForNodeInfo = containingDocument.getInstanceForNode(currentNodeInfo);
            invalidInstances.add(instanceForNodeInfo.getEffectiveId());
        }
    }

    private boolean validateType(Bind bind, NodeInfo currentNodeInfo, boolean required) {

        final boolean typeValid;
        {
            // NOTE: xf:bind/@type is a literal type value, and it is the same that applies to all nodes pointed to by xf:bind/@ref
            final QName typeQName = bind.typeQName;

            final String typeNamespaceURI = typeQName.getNamespaceURI();
            final String typeLocalname = typeQName.getName();

            // Get value to validate if not already computed above

            final String nodeValue = DataModel.getValue(currentNodeInfo);

            // TODO: "[...] these datatypes can be used in the type model item property without the addition of the
            // XForms namespace qualifier if the namespace context has the XForms namespace as the default
            // namespace."

            final boolean isBuiltInSchemaType = XMLConstants.XSD_URI.equals(typeNamespaceURI);
            final boolean isBuiltInXFormsType = XFormsConstants.XFORMS_NAMESPACE_URI.equals(typeNamespaceURI);
            final boolean isBuiltInXXFormsType = XFormsConstants.XXFORMS_NAMESPACE_URI.equals(typeNamespaceURI);

            if (isBuiltInXFormsType && Model.jXFormsSchemaTypeNames().contains(typeLocalname)) {
                // xf:dayTimeDuration, xf:yearMonthDuration, xf:email, xf:card-number
                if (xformsValidator == null) {
                    xformsValidator = new XFormsModelSchemaValidator("oxf:/org/orbeon/oxf/xforms/xforms-types.xsd");
                    xformsValidator.loadSchemas(containingDocument);
                }

                final String validationError =
                    xformsValidator.validateDatatype(nodeValue, typeNamespaceURI, typeLocalname, typeQName.getQualifiedName(),
                            bind.staticBind.locationData());

                typeValid = validationError == null;

            } else if (isBuiltInXFormsType && nodeValue.length() == 0) {
                // Don't consider the node invalid if the string is empty with xf:* types
                typeValid = true;
            } else if (isBuiltInSchemaType || isBuiltInXFormsType) {
                // Built-in schema or XForms type

                // Use XML Schema namespace URI as Saxon doesn't know anything about XForms types
                final String newTypeNamespaceURI = XMLConstants.XSD_URI;

                // Get type information
                final int requiredTypeFingerprint = StandardNames.getFingerprint(newTypeNamespaceURI, typeLocalname);
                if (requiredTypeFingerprint == -1) {
                    throw new ValidationException("Invalid schema type '" + bind.staticBind.getType() + "'", bind.staticBind.locationData());

                    // TODO: xxx check what XForms event must be dispatched
                }

                // Need an evaluator to check and convert type below
                final XPathEvaluator xpathEvaluator;
                try {
                    xpathEvaluator = new XPathEvaluator();
                    // NOTE: Not sure declaring namespaces here is necessary just to perform the cast
                    final IndependentContext context = (IndependentContext) xpathEvaluator.getStaticContext();
                    for (final Map.Entry<String, String> entry : bind.staticBind.namespaceMapping().mapping.entrySet()) {
                        context.declareNamespace(entry.getKey(), entry.getValue());
                    }
                } catch (Exception e) {
                    throw ValidationException.wrapException(e, bind.staticBind.locationData());

                    // TODO: xxx check what XForms event must be dispatched
                }

                // Try to perform casting
                // TODO: Should we actually perform casting? This for example removes leading and trailing space around tokens. Is that expected?
                final StringValue stringValue = new StringValue(nodeValue);
                final XPathContext xpContext = new XPathContextMajor(stringValue, xpathEvaluator.getExecutable());
                final ConversionResult result = stringValue.convertPrimitive((BuiltInAtomicType) BuiltInType.getSchemaType(requiredTypeFingerprint), true, xpContext);

                // Set error on node if necessary
                typeValid = !(result instanceof ValidationFailure);
            } else if (isBuiltInXXFormsType) {
                // Built-in extension types
                final boolean isOptionalAndEmpty = !required && "".equals(nodeValue);
                if (typeLocalname.equals("xml")) {
                    // xxf:xml type
                    typeValid = isOptionalAndEmpty || XMLUtils.isWellFormedXML(nodeValue);
                } else if (typeLocalname.equals("xpath2")) {
                    // xxf:xpath2 type

                    // Find element which scopes namespaces
                    final NodeInfo namespaceNodeInfo;
                    if (currentNodeInfo.getNodeKind() == Node.ELEMENT_NODE)
                        namespaceNodeInfo = currentNodeInfo;
                    else
                        namespaceNodeInfo = currentNodeInfo.getParent();

                    if (namespaceNodeInfo != null && namespaceNodeInfo.getNodeKind() == Node.ELEMENT_NODE) {
                        // ASSUMPTION: Binding to dom4j-backed node (which InstanceData assumes too)
                        final Element namespaceElement = XML.unwrapElement(namespaceNodeInfo);
                        final NamespaceMapping namespaceMapping = new NamespaceMapping(Dom4jUtils.getNamespaceContextNoDefault(namespaceElement));
                        typeValid = isOptionalAndEmpty || XPath.isXPath2Expression(nodeValue, namespaceMapping, bind.staticBind.locationData(), indentedLogger);
                    } else {
                        // This means that we are bound to a node which is not an element and which does not have a
                        // parent element. This could be a detached attribute, or an element node, etc. Unsure if we
                        // would have made it this far anyway! We can't validate the expression so we only consider
                        // the "optional-and-empty" case.
                        typeValid = isOptionalAndEmpty;
                    }
                } else {
                    throw new ValidationException("Invalid schema type '" + bind.staticBind.getType() + "'", bind.staticBind.locationData());

                    // TODO: xxx check what XForms event must be dispatched
                }

            } else if (model.hasSchema()) {
                // Other type and there is a schema

                // There are possibly types defined in the schema
                final String validationError
                        = model.getSchemaValidator().validateDatatype(nodeValue, typeNamespaceURI, typeLocalname, typeQName.getQualifiedName(), bind.staticBind.locationData());

                typeValid = validationError == null;
            } else {
                throw new ValidationException("Invalid schema type '" + bind.staticBind.getType() + "'", bind.staticBind.locationData());

                // TODO: xxx check what XForms event must be dispatched
            }
        }
        return typeValid;
    }

    public static boolean isEmptyValue(String value) {
        // TODO: configurable notion of "empty" through property (trimming vs. strict)
        return "".equals(value);
    }

    private Boolean evaluateConstraintMIP(Bind bind, int position) {
        if (bind.staticBind.getConstraint() != null) {
            // Evaluate constraint
            try {
                // Get MIP value
                return evaluateBooleanExpression(bind.nodeset, position, bind.staticBind.getConstraint());
            } catch (Exception e) {
                handleMIPXPathException(e, bind, bind.staticBind.getConstraint(), "evaluating XForms constraint bind");
                return ! Model.DEFAULT_VALID(); // https://github.com/orbeon/orbeon-forms/issues/835
            }
        } else {
            return null;
        }
    }

    private static interface BindRunner {
        public void applyBind(Bind bind, int position);
    }

    public class Bind implements XFormsObject {

        public final BindTree.Bind staticBind;
        public final List<Item> nodeset;       // actual nodeset for this bind

        public final QName typeQName;

        private List<BindNode> bindNodes; // List<BindIteration>

        public XFormsContainingDocument containingDocument() {
            return XFormsModelBinds.this.containingDocument;
        }

        // To work around Scala compiler bug ("error: not found: value BindTree") when accessing staticBind directly
        public NamespaceMapping namespaceMapping() {
            return staticBind.namespaceMapping();
        }

        public Bind(BindTree.Bind staticBind, boolean isSingleNodeContext) {
            this.staticBind = staticBind;

            // Compute nodeset for this bind
            model.getContextStack().pushBinding(staticBind.element(), model.getEffectiveId(), model.getResolutionScope());
            {
                // NOTE: This should probably go into XFormsContextStack
                if (model.getContextStack().getCurrentBindingContext().isNewBind()) {
                    // Case where a @nodeset or @ref attribute is present -> a current nodeset is therefore available
                    nodeset = model.getContextStack().getCurrentNodeset();
                } else {
                    // Case where of missing @nodeset attribute (it is optional in XForms 1.1 and defaults to the context item)
                    final Item contextItem = model.getContextStack().getContextItem();
                    nodeset = (contextItem == null) ? XFormsConstants.EMPTY_ITEM_LIST : Collections.singletonList(contextItem);
                }

                assert nodeset != null;

                // "4.7.2 References to Elements within a bind Element [...] If a target bind element is outermost, or if
                // all of its ancestor bind elements have nodeset attributes that select only one node, then the target bind
                // only has one associated bind object, so this is the desired target bind object whose nodeset is used in
                // the Single Node Binding or Node Set Binding"
                if (isSingleNodeContext)
                    singleNodeContextBinds.put(staticBind.staticId(), this);

                // Set type on node
                // Get type namespace and local name
                typeQName = evaluateTypeQName(staticBind.namespaceMapping().mapping);

                final int nodesetSize = nodeset.size();
                if (nodesetSize > 0) {
                    // Only then does it make sense to create BindNodes
                    
                    final List<BindTree.Bind> childrenStaticBinds = staticBind.jChildren();
                    if (childrenStaticBinds.size() > 0) {
                        // There are children binds (and maybe MIPs)
                        bindNodes = new ArrayList<BindNode>(nodesetSize);
    
                        // Iterate over nodeset and produce child iterations
                        int currentPosition = 1;
                        for (final Item item : nodeset) {
                            model.getContextStack().pushIteration(currentPosition);
                            {
                                // Create iteration and remember it
                                final boolean isNewSingleNodeContext = isSingleNodeContext && nodesetSize == 1;
                                final BindIteration currentBindIteration = new BindIteration(getStaticId(), isNewSingleNodeContext, item, childrenStaticBinds, typeQName);
                                bindNodes.add(currentBindIteration);
    
                                // Create mapping context node -> iteration
                                final NodeInfo iterationNodeInfo = (NodeInfo) nodeset.get(currentPosition - 1);
                                List<BindIteration> iterations = iterationsForContextNodeInfo.get(iterationNodeInfo);
                                if (iterations == null) {
                                    iterations = new ArrayList<BindIteration>();
                                    iterationsForContextNodeInfo.put(iterationNodeInfo, iterations);
                                }
                                iterations.add(currentBindIteration);
                            }
                            model.getContextStack().popBinding();
    
                            currentPosition++;
                        }
                    } else if (staticBind.hasMIPs()) {
                        // No children binds, but we have MIPs, so create holders anyway
                        bindNodes = new ArrayList<BindNode>(nodesetSize);
    
                        for (final Item item : nodeset)
                            bindNodes.add(new BindNode(getStaticId(), item, typeQName));
                    }
                }

            }
            model.getContextStack().popBinding();
        }

        public void applyBinds(BindRunner bindRunner) {
            if (nodeset.size() > 0) {
                // Handle each node in this node-set
                final Iterator<BindNode> j = (bindNodes != null) ? bindNodes.iterator() : null;

                for (int index = 1; index <= nodeset.size(); index++) {
                    final BindNode currentBindIteration = (j != null) ? j.next() : null;

                    // Handle current node
                    bindRunner.applyBind(this, index);

                    // Handle children binds if any
                    if (currentBindIteration instanceof BindIteration)
                        ((BindIteration) currentBindIteration).applyBinds(bindRunner);
                }
            }
        }

        public String getStaticId() {
            return staticBind.staticId();
        }

        public String getEffectiveId() {
            return XFormsUtils.getRelatedEffectiveId(model.getEffectiveId(), getStaticId());
        }

        private QName evaluateTypeQName(Map<String, String> namespaceMap) {
            final String typeQNameString = staticBind.getType();
            if (typeQNameString != null) {
                final String typeNamespacePrefix;
                final String typeNamespaceURI;
                final String typeLocalname;

                final int prefixPosition = typeQNameString.indexOf(':');
                if (prefixPosition > 0) {
                    typeNamespacePrefix = typeQNameString.substring(0, prefixPosition);
                    typeNamespaceURI = namespaceMap.get(typeNamespacePrefix);
                    if (typeNamespaceURI == null)
                        throw new ValidationException("Namespace not declared for prefix '" + typeNamespacePrefix + "'", staticBind.locationData());

                    // TODO: xxx check what XForms event must be dispatched

                    typeLocalname = typeQNameString.substring(prefixPosition + 1);
                } else {
                    typeNamespacePrefix = "";
                    typeNamespaceURI = "";
                    typeLocalname = typeQNameString;
                }

                return QName.get(typeLocalname, new Namespace(typeNamespacePrefix, typeNamespaceURI));
            } else {
                return null;
            }
        }

        private BindNode getBindNode(int position) {
            return (bindNodes != null) ? bindNodes.get(position - 1) : null;
        }

        // Delegate to BindNode
        public void setRelevant(int position, boolean value) {
            getBindNode(position).setRelevant(value);
        }

        public void setReadonly(int position, boolean value) {
            getBindNode(position).setReadonly(value);
        }

        public void setRequired(int position, boolean value) {
            getBindNode(position).setRequired(value);
        }

        public void setCustom(int position, String name, String value) {
            getBindNode(position).setCustom(name, value);
        }

        public void setTypeValidity(int position, boolean value) {
            getBindNode(position).setTypeValidity(value);
        }

        public void setRequiredValidity(int position, boolean value) {
            getBindNode(position).setRequiredValidity(value);
        }

        public void setConstraintValidity(int position, boolean value) {
            getBindNode(position).setConstraintValidity(value);
        }

        public boolean isValid(int position) {
            return getBindNode(position).isValid();
        }

        // Bind node that also contains nested binds
        private class BindIteration extends BindNode {// TODO: if bind doesn't have MIPs, BindNode storage is not needed

            private List<Bind> childrenBinds;

            public BindIteration(String bindStaticId, boolean isSingleNodeContext, Item item, List<BindTree.Bind> childrenStaticBinds, QName typeQName) {

                super(bindStaticId, item, typeQName);

                assert childrenStaticBinds.size() > 0;

                // Iterate over children and create children binds
                childrenBinds = new ArrayList<Bind>(childrenStaticBinds.size());
                for (final BindTree.Bind staticBind : childrenStaticBinds)
                    childrenBinds.add(new Bind(staticBind, isSingleNodeContext));
            }

            public void applyBinds(BindRunner bindRunner) {
                for (final Bind currentBind : childrenBinds)
                    currentBind.applyBinds(bindRunner);
            }

            public Bind getBind(String bindId) {
                for (final Bind currentBind : childrenBinds)
                    if (currentBind.staticBind.staticId().equals(bindId))
                        return currentBind;
                return null;
            }
        }
    }

    // BindNode holds MIP values for a given bind node
    public static class BindNode {

        // Current MIP state
        private boolean relevant = Model.DEFAULT_RELEVANT();
        protected boolean readonly = Model.DEFAULT_READONLY();
        private boolean required = Model.DEFAULT_REQUIRED();
        private Map<String, String> customMips = null;

        private boolean typeValidity = Model.DEFAULT_VALID();
        private boolean requiredValidity = Model.DEFAULT_VALID();
        private boolean constraintValidity = Model.DEFAULT_VALID();

        public final String bindStaticId;
        public final NodeInfo nodeInfo;
        public final boolean hasChildrenElements;

        public BindNode(String bindStaticId, Item item, QName typeQName) {
            this.bindStaticId = bindStaticId;
            if (item instanceof NodeInfo) {
                nodeInfo = (NodeInfo) item;
                hasChildrenElements = nodeInfo.getNodeKind() == org.w3c.dom.Node.ELEMENT_NODE && XML.hasChildElement(nodeInfo);

                // Add us to the node
                InstanceData.addBindNode(nodeInfo, this);
                if (typeQName != null)
                    InstanceData.setBindType(nodeInfo, typeQName);
            } else {
                nodeInfo = null;
                hasChildrenElements = false;
            }
        }

        public String getBindStaticId() {
            return bindStaticId;
        }

        public void setRelevant(boolean value) {
            this.relevant = value;
        }

        public void setReadonly(boolean value) {
            this.readonly = value;
        }

        public void setRequired(boolean value) {
            this.required = value;
        }

        public void setCustom(String name, String value) {
            if (customMips == null)
                customMips = new HashMap<String, String>(); // maybe should be LinkedHashMap for reproducibility
            customMips.put(name, value);
        }

        public void setTypeValidity(boolean value) {
            this.typeValidity = value;
        }

        public void setRequiredValidity(boolean value) {
            this.requiredValidity = value;
        }

        public void setConstraintValidity(boolean value) {
            this.constraintValidity = value;
        }

        public boolean isRelevant() {
            return relevant;
        }

        public boolean isReadonly() {
            return readonly;
        }

        public boolean isRequired() {
            return required;
        }

        public boolean isValid() {
            return typeValidity && requiredValidity && constraintValidity;
        }

        public boolean isTypeValid() {
            return typeValidity;
        }

        public boolean isConstraintValidity() {
            return constraintValidity;
        }

        public Map<String, String> getCustomMips() {
            return customMips == null ? null : Collections.unmodifiableMap(customMips);
        }
    }
}
