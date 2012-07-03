/**
 * Copyright (C) 2011 Akiban Technologies Inc. This program is free software:
 * you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License, version 3, as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */
package com.akiban.sql.optimizer.explain;

import com.akiban.sql.optimizer.explain.std.ExpressionExplainer;
import java.util.Map;

public class Formatter {

    String describe(Explainer explainer) {
        StringBuilder sb = new StringBuilder("");
        describe(explainer, sb);
        return sb.toString();
    }
    
    void describe(Explainer explainer, StringBuilder sb) {
        describe(explainer, sb, true);
    }

    void describe(Explainer explainer, StringBuilder sb, boolean noParens) {
        if (explainer.hasAttributes())
        {
            OperationExplainer opEx = (OperationExplainer) explainer;
            describeOperation(opEx, sb, noParens);
        }
        else
        {
            PrimitiveExplainer primEx = (PrimitiveExplainer) explainer;
            describePrimitive(primEx, sb);
        }
    }

    void describeOperation(OperationExplainer explainer, StringBuilder sb, boolean noParens) {
        
        Attributes atts = (Attributes) explainer.get().clone();
        
        if (explainer.get().containsKey(Label.INFIX))
        {
            Explainer leftExplainer = atts.valuePairs().get(0).getValue();
            Explainer rightExplainer = atts.valuePairs().get(1).getValue();
            if (!noParens)
                sb.append("(");
            boolean associative = explainer.get().containsKey(Label.ASSOCIATIVE);   
            describe(leftExplainer, sb, associative && explainer.equals(leftExplainer));
            sb.append(" ").append(atts.get(Label.NAME).get(0).get()).append(" ");
            describe(rightExplainer, sb, associative && explainer.equals(rightExplainer));
            if (!noParens)
                sb.append(")");
        }
        else
        {
            sb.append(atts.get(Label.NAME).get(0).get());
            atts.remove(Label.NAME);
            sb.append("(");
            for (Map.Entry<Label, Explainer> entry : atts.valuePairs())
            {
                describe(entry.getValue(), sb);
                sb.append(", ");
            }
            sb.setLength(sb.length()-2);
            sb.append(")");
        }
    }

    void describePrimitive(PrimitiveExplainer explainer, StringBuilder sb) {
        if (explainer.getType()==Type.STRING)
        {
            sb.append("\"").append(explainer.get()).append("\"");
        }
        else
        {
            sb.append(explainer.get());
        }
    }
}
