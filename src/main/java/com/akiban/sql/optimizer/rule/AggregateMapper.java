/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule;

import com.akiban.server.error.UnsupportedSQLException;

import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.TypeId;

import com.akiban.sql.optimizer.plan.*;

import java.util.*;

/** Resolve aggregate functions and group by expressions to output
 * columns of the "group table," that is, the result of aggregation.
 */
public class AggregateMapper extends BaseRule
{
    @Override
    public PlanNode apply(PlanNode plan) {
        Collector c = new Collector();
        plan.accept(c);
        for (AggregateSource source : c.found) {
            Mapper m = new Mapper(source);
            m.remap(source);
        }
        return plan;
    }

    static class Collector implements PlanVisitor, ExpressionVisitor {
        List<AggregateSource> found = new ArrayList<AggregateSource>();

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }
        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }
        @Override
        public boolean visit(PlanNode n) {
            if (n instanceof AggregateSource)
                found.add((AggregateSource)n);
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }
        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }
        @Override
        public boolean visit(ExpressionNode n) {
            return true;
        }
    }

    static class Mapper implements ExpressionRewriteVisitor {
        private AggregateSource source;
        private Map<ExpressionNode,ExpressionNode> map = 
            new HashMap<ExpressionNode,ExpressionNode>();

        public Mapper(AggregateSource source) {
            this.source = source;
            // Map all the group by expressions at the start.
            // This means that if you GROUP BY x+1, you can ORDER BY
            // x+1, or x+1+1, but not x+2. Postgres is like that, too.
            List<ExpressionNode> groupBy = source.getGroupBy();
            for (int i = 0; i < groupBy.size(); i++) {
                ExpressionNode expr = groupBy.get(i);
                map.put(expr, new ColumnExpression(source, i, 
                                                   expr.getSQLtype(), expr.getSQLsource()));
            }
        }

        public void remap(PlanNode n) {
            while (true) {
                // Keep going as long as we're feeding something we understand.
                n = n.getOutput();
                if (n instanceof Filter) {
                    remap(((Filter)n).getConditions());
                }
                else if (n instanceof Sort) {
                    remapA(((Sort)n).getOrderBy());
                }
                else if (n instanceof ResultSet) {
                    remapA(((ResultSet)n).getResults());
                }
                else
                    break;
            }
        }

        protected void remap(Collection<? extends ExpressionNode> exprs) {
            // TODO: If there were a boolean aggregate function, we'd
            // need to be able to do HAVING F(X) and so would need to
            // replace it in the list. As it is, we only replace nodes
            // beneath.
            for (ExpressionNode expr : exprs) {
                expr.accept(this);
            }
        }

        protected void remapA(Collection<? extends AnnotatedExpression> exprs) {
            for (AnnotatedExpression expr : exprs) {
                expr.setExpression(expr.getExpression().accept(this));
            }
        }

        @Override
        public boolean visitChildrenFirst(ExpressionNode expr) {
            return false;
        }

        @Override
        public ExpressionNode visit(ExpressionNode expr) {
            ExpressionNode nexpr = map.get(expr);
            if (nexpr != null)
                return nexpr;
            if (expr instanceof AggregateFunctionExpression) {
                return addAggregate((AggregateFunctionExpression)expr);
            }
            if (expr instanceof ColumnExpression) {
                if (((ColumnExpression)expr).getTable() != source) {
                    // MySQL adds an implicit FIRST (not first not-null) aggregate function.
                    throw new UnsupportedSQLException("Column cannot be used outside aggregate function or GROUP BY", expr.getSQLsource());
                }
            }
            return expr;
        }

        protected ExpressionNode addAggregate(AggregateFunctionExpression expr) {
            ExpressionNode nexpr = rewrite(expr);
            if (nexpr != null)
                return nexpr.accept(this);
            int position = source.addAggregate((AggregateFunctionExpression)expr);
            nexpr = new ColumnExpression(source, position, 
                                         expr.getSQLtype(), expr.getSQLsource());
            map.put(expr, nexpr);
            return nexpr;
        }

        // Rewrite agregate functions that aren't well behaved wrt pre-aggregation.
        protected ExpressionNode rewrite(AggregateFunctionExpression expr) {
            String function = expr.getFunction();
            if ("AVG".equals(function)) {
                ExpressionNode operand = expr.getOperand();
                List<ExpressionNode> noperands = new ArrayList<ExpressionNode>(2);
                noperands.add(new AggregateFunctionExpression("SUM", operand, false,
                                                              operand.getSQLtype(), null));
                noperands.add(new AggregateFunctionExpression("COUNT", operand, false,
                                                              new DataTypeDescriptor(TypeId.INTEGER_ID, false), null));
                return new FunctionExpression("divide",
                                              noperands,
                                              expr.getSQLtype(), expr.getSQLsource());
            }
            // TODO: {VAR,STDDEV}_{POP,SAMP}
            return null;
        }
    }

}