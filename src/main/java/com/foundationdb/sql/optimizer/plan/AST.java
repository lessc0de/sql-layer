/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.optimizer.plan;

import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.parser.DMLStatementNode;
import com.foundationdb.sql.parser.ParameterNode;
import com.foundationdb.sql.unparser.NodeToString;

import java.util.List;

/** A parsed (and type-bound, normalized, etc.) SQL query.
 */
public class AST extends BasePlanNode
{
    private DMLStatementNode statement;
    private List<ParameterNode> parameters;

    public AST(DMLStatementNode statement, List<ParameterNode> parameters) {
        this.statement = statement;
        this.parameters = parameters;
    }
    
    public DMLStatementNode getStatement() {
        return statement;
    }

    public List<ParameterNode> getParameters() {
        return parameters;
    }

    @Override
    public boolean accept(PlanVisitor v) {
        return v.visit(this);
    }
    
    @Override
    public String summaryString() {
        StringBuilder str = new StringBuilder(super.summaryString());
        str.append("(");
        try {
            str.append(new NodeToString().toString(statement));
        }
        catch (StandardException ex) {
        }
        str.append(")");
        return str.toString();
    }

    @Override
    protected void deepCopy(DuplicateMap map) {
        super.deepCopy(map);
        // Do not copy AST.
    }

}
