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
package org.orbeon.oxf.xforms.function;

import org.orbeon.oxf.util.SecureUtils;
import org.orbeon.saxon.expr.Expression;
import org.orbeon.saxon.expr.XPathContext;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.value.StringValue;

/**
 * XForms hmac() function (XForms 1.1).
 */
public class Hmac extends XFormsFunction {

    public Item evaluateItem(XPathContext xpathContext) throws XPathException {

    	final Expression keyExpression = argument[0];
        final Expression dataExpression = argument[1];
        final Expression algorithmExpression = argument[2];
        final Expression encodingExpression = argument.length >= 4 ? argument[3] : null;

        final String key = keyExpression.evaluateAsString(xpathContext).toString();
        final String data = dataExpression.evaluateAsString(xpathContext).toString();
        final String algorithm = algorithmExpression.evaluateAsString(xpathContext).toString();
        final String encoding = encodingExpression != null? encodingExpression.evaluateAsString(xpathContext).toString() : "base64";

        // Create digest
        final String result = SecureUtils.hmacString(key, data, algorithm, encoding);

        return new StringValue(result);
    }
}
