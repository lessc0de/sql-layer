/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule.join_enum;

import com.akiban.sql.optimizer.rule.EquivalenceFinder;
import com.akiban.sql.optimizer.rule.cost.PlanCostEstimator;
import com.akiban.sql.optimizer.rule.join_enum.DPhyp.JoinOperator;
import com.akiban.sql.optimizer.rule.range.ColumnRanges;

import com.akiban.sql.optimizer.plan.*;
import com.akiban.sql.optimizer.plan.Sort.OrderByExpression;
import com.akiban.sql.optimizer.plan.TableGroupJoinTree.TableGroupJoinNode;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.Index.JoinType;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.UserTable;
import com.akiban.server.expression.std.Comparison;
import com.akiban.server.types.AkType;
import com.akiban.server.geophile.Space;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.Types3Switch;
import com.akiban.sql.optimizer.TypesTranslation;
import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.TypeId;

import com.google.common.base.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** The goal of a indexes within a group. */
public class GroupIndexGoal implements Comparator<BaseScan>
{
    private static final Logger logger = LoggerFactory.getLogger(GroupIndexGoal.class);
    static volatile Function<? super IndexScan,Void> intersectionEnumerationHook = null;

    // The overall goal.
    private QueryIndexGoal queryGoal;
    // The grouped tables.
    private TableGroupJoinTree tables;
    
    // All the conditions that might be indexable.
    private List<ConditionExpression> conditions;
    // Where they came from.
    private List<ConditionList> conditionSources;

    // All the columns besides those in conditions that will be needed.
    private RequiredColumns requiredColumns;

    // Tables already bound outside.
    private Set<ColumnSource> boundTables;

    // Can an index be used to take care of sorting.
    // TODO: Make this a subset of queryGoal's sorting, based on what
    // tables are in what join, rather than an all or nothing.
    private boolean sortAllowed;

    // Mapping of Range-expressible conditions, by their column. lazy loaded.
    private Map<ColumnExpression,ColumnRanges> columnsToRanges;
    
    public GroupIndexGoal(QueryIndexGoal queryGoal, TableGroupJoinTree tables) {
        this.queryGoal = queryGoal;
        this.tables = tables;

        if (queryGoal.getWhereConditions() != null) {
            conditionSources = Collections.singletonList(queryGoal.getWhereConditions());
            conditions = queryGoal.getWhereConditions();
        }
        else {
            conditionSources = Collections.emptyList();
            conditions = Collections.emptyList();
        }

        requiredColumns = new RequiredColumns(tables);

        boundTables = queryGoal.getQuery().getOuterTables();
        sortAllowed = true;
    }

    public QueryIndexGoal getQueryGoal() {
        return queryGoal;
    }

    public TableGroupJoinTree getTables() {
        return tables;
    }

    /**
     * @param boundTables Tables already bound by the outside
     * @param queryJoins Joins that come from the query, or part of the query, that an index is being searched for.
     *                   Will generally, but not in the case of a sub-query, match <code>joins</code>.
     * @param joins Joins that apply to this part of the query.
     * @param outsideJoins All joins for this query.
     * @param sortAllowed <code>true</code> if sorting is allowed
     *
     * @return Full list of all usable condition sources.
     */
    public List<ConditionList> updateContext(Set<ColumnSource> boundTables,
                                             Collection<JoinOperator> queryJoins,
                                             Collection<JoinOperator> joins,
                                             Collection<JoinOperator> outsideJoins,
                                             boolean sortAllowed) {
        setBoundTables(boundTables);
        this.sortAllowed = sortAllowed;
        setJoinConditions(queryJoins, joins);
        updateRequiredColumns(joins, outsideJoins);
        return conditionSources;
    }

    public void setBoundTables(Set<ColumnSource> boundTables) {
        this.boundTables = boundTables;
    }

    private static boolean hasOuterJoin(Collection<JoinOperator> joins) {
        for (JoinOperator joinOp : joins) {
            switch (joinOp.getJoinType()) {
                case LEFT:
                case RIGHT:
                case FULL_OUTER:
                    return true;
            }
        }
        return false;
    }
    
    public void setJoinConditions(Collection<JoinOperator> queryJoins, Collection<JoinOperator> joins) {
        conditionSources = new ArrayList<ConditionList>();
        if ((queryGoal.getWhereConditions() != null) && !hasOuterJoin(queryJoins)) {
            conditionSources.add(queryGoal.getWhereConditions());
        }
        for (JoinOperator join : joins) {
            ConditionList joinConditions = join.getJoinConditions();
            if (joinConditions != null)
                conditionSources.add(joinConditions);
        }
        switch (conditionSources.size()) {
        case 0:
            conditions = Collections.emptyList();
            break;
        case 1:
            conditions = conditionSources.get(0);
            break;
        default:
            conditions = new ArrayList<ConditionExpression>();
            for (ConditionList conditionSource : conditionSources) {
                conditions.addAll(conditionSource);
            }
        }
    }

    public void updateRequiredColumns(Collection<JoinOperator> joins,
                                      Collection<JoinOperator> outsideJoins) {
        requiredColumns.clear();
        Collection<PlanNode> orderings = (queryGoal.getOrdering() == null) ? 
            Collections.<PlanNode>emptyList() : 
            Collections.<PlanNode>singletonList(queryGoal.getOrdering());
        RequiredColumnsFiller filler = new RequiredColumnsFiller(requiredColumns, 
                                                                 orderings, conditions);
        queryGoal.getQuery().accept(filler);
        for (JoinOperator join : outsideJoins) {
            if (joins.contains(join)) continue;
            ConditionList joinConditions = join.getJoinConditions();
            if (joinConditions != null) {
                for (ConditionExpression condition : joinConditions) {
                    condition.accept(filler);
                }
            }
        }        
    }

    /** Populate given index usage according to goal.
     * @return <code>false</code> if the index is useless.
     */
    public boolean usable(SingleIndexScan index) {
        int nequals = insertLeadingEqualities(index, conditions);
        if (index.getIndex().isSpatial()) return spatialUsable(index, nequals);
        List<ExpressionNode> indexExpressions = index.getColumns();
        if (nequals < indexExpressions.size()) {
            ExpressionNode indexExpression = indexExpressions.get(nequals);
            if (indexExpression != null) {
                boolean foundInequalityCondition = false;
                for (ConditionExpression condition : conditions) {
                    if (condition instanceof ComparisonCondition) {
                        ComparisonCondition ccond = (ComparisonCondition)condition;
                        if (ccond.getOperation() == Comparison.NE)
                            continue; // ranges are better suited for !=
                        ExpressionNode otherComparand = matchingComparand(indexExpression, ccond);
                        if (otherComparand != null) {
                            Comparison op = ccond.getOperation();
                            if (otherComparand == ccond.getLeft())
                                op = ComparisonCondition.reverseComparison(op);
                            index.addInequalityCondition(condition, op, otherComparand);
                            foundInequalityCondition = true;
                        }
                    }
                }
                if (!foundInequalityCondition) {
                    ColumnRanges range = rangeForIndex(indexExpression);
                    if (range != null)
                        index.addRangeCondition(range);
                }
            }
        }
        index.setOrderEffectiveness(determineOrderEffectiveness(index));
        index.setCovering(determineCovering(index));
        if ((index.getOrderEffectiveness() == IndexScan.OrderEffectiveness.NONE) &&
            !index.hasConditions() &&
            !index.isCovering())
            return false;
        index.setCostEstimate(estimateCost(index));
        return true;
    }

    private int insertLeadingEqualities(SingleIndexScan index, List<ConditionExpression> localConds) {
        setColumnsAndOrdering(index);
        int nequals = 0;
        List<ExpressionNode> indexExpressions = index.getColumns();
        int ncols = indexExpressions.size();
        while (nequals < ncols) {
            ExpressionNode indexExpression = indexExpressions.get(nequals);
            if (indexExpression == null) break;
            ConditionExpression equalityCondition = null;
            ExpressionNode otherComparand = null;
            for (ConditionExpression condition : localConds) {
                if (condition instanceof ComparisonCondition) {
                    ComparisonCondition ccond = (ComparisonCondition)condition;
                    if (ccond.getOperation() != Comparison.EQ)
                        continue; // only doing equalities
                    ExpressionNode comparand = matchingComparand(indexExpression, ccond);
                    if (comparand != null) {
                        equalityCondition = condition;
                        otherComparand = comparand;
                        break;
                    }
                }
                else if (condition instanceof FunctionCondition) {
                    FunctionCondition fcond = (FunctionCondition)condition;
                    if (fcond.getFunction().equals("isNull") &&
                        (fcond.getOperands().size() == 1) &&
                        indexExpressionMatches(indexExpression, 
                                               fcond.getOperands().get(0))) {
                        equalityCondition = condition;
                        otherComparand = null; // TODO: Or constant NULL, depending on API.
                        break;
                    }
                }
            }
            if (equalityCondition == null)
                break;
            index.addEqualityCondition(equalityCondition, otherComparand);
            nequals++;
        }
        return nequals;
    }

    private ExpressionNode matchingComparand(ExpressionNode indexExpression, 
                                             ComparisonCondition ccond) {
        ExpressionNode comparand;
        if (indexExpressionMatches(indexExpression, ccond.getLeft())) {
            comparand = ccond.getRight();
            if (constantOrBound(comparand))
                return comparand;
        }
        if (indexExpressionMatches(indexExpression, ccond.getRight())) {
            comparand = ccond.getLeft();
            if (constantOrBound(comparand))
                return comparand;
        }
        return null;
    }

    private static void setColumnsAndOrdering(SingleIndexScan index) {
        List<IndexColumn> indexColumns = index.getAllColumns();
        int ncols = indexColumns.size();
        int firstSpatialColumn, dimensions;
        SpecialIndexExpression.Function spatialFunction;
        if (index.getIndex().isSpatial()) {
            TableIndex spatialIndex = (TableIndex)index.getIndex();
            firstSpatialColumn = spatialIndex.firstSpatialArgument();
            dimensions = spatialIndex.dimensions();
            assert (dimensions == Space.LAT_LON_DIMENSIONS);
            spatialFunction = SpecialIndexExpression.Function.Z_ORDER_LAT_LON;
        }
        else {
            firstSpatialColumn = Integer.MAX_VALUE;
            dimensions = 0;
            spatialFunction = null;
        }
        List<OrderByExpression> orderBy = new ArrayList<OrderByExpression>(ncols);
        List<ExpressionNode> indexExpressions = new ArrayList<ExpressionNode>(ncols);
        int i = 0;
        while (i < ncols) {
            ExpressionNode indexExpression;
            boolean ascending;
            if (i == firstSpatialColumn) {
                List<ExpressionNode> operands = new ArrayList<ExpressionNode>(dimensions);
                for (int j = 0; j < dimensions; j++) {
                    operands.add(getIndexExpression(index, indexColumns.get(i++)));
                }
                indexExpression = new SpecialIndexExpression(spatialFunction, operands);
                ascending = true;
            }
            else {
                IndexColumn indexColumn = indexColumns.get(i++);
                indexExpression = getIndexExpression(index, indexColumn);
                ascending = indexColumn.isAscending();
            }
            indexExpressions.add(indexExpression);
            orderBy.add(new OrderByExpression(indexExpression, ascending));
        }
        index.setColumns(indexExpressions);
        index.setOrdering(orderBy);
    }

    // Take ordering from output index and adjust ordering of others to match.
    private static void installOrdering(IndexScan index, 
                                        List<OrderByExpression> outputOrdering,
                                        int outputPeggedCount, int comparisonFields) {
        if (index instanceof SingleIndexScan) {
            List<OrderByExpression> indexOrdering = index.getOrdering();
            if ((indexOrdering != null) && (indexOrdering != outputOrdering)) {
                // Order comparison fields the same way as output.
                // Try to avoid mixed mode: initial columns ordered
                // like first comparison, trailing columns ordered
                // like last comparison.
                int i = 0;
                while (i < index.getPeggedCount()) {
                    indexOrdering.get(i++).setAscending(outputOrdering.get(outputPeggedCount).isAscending());
                }
                for (int j = 0; j < comparisonFields; j++) {
                    indexOrdering.get(i++).setAscending(outputOrdering.get(outputPeggedCount + j).isAscending());
                }
                while (i < indexOrdering.size()) {
                    indexOrdering.get(i++).setAscending(outputOrdering.get(outputPeggedCount + comparisonFields - 1).isAscending());
                }
            }
        }
        else if (index instanceof MultiIndexIntersectScan) {
            MultiIndexIntersectScan multiIndex = (MultiIndexIntersectScan)index;
            installOrdering(multiIndex.getOutputIndexScan(), 
                            outputOrdering, outputPeggedCount, comparisonFields);
            installOrdering(multiIndex.getSelectorIndexScan(), 
                            outputOrdering, outputPeggedCount, comparisonFields);
        }
    }

    // Determine how well this index does against the target.
    // Also, correct traversal order to match sort if possible.
    protected IndexScan.OrderEffectiveness
        determineOrderEffectiveness(SingleIndexScan index) {
        IndexScan.OrderEffectiveness result = IndexScan.OrderEffectiveness.NONE;
        if (!sortAllowed) return result;
        List<OrderByExpression> indexOrdering = index.getOrdering();
        if (indexOrdering == null) return result;
        BitSet reverse = new BitSet(indexOrdering.size());
        int nequals = index.getNEquality();
        List<ExpressionNode> equalityColumns = null;
        if (nequals > 0) {
            equalityColumns = index.getColumns().subList(0, nequals);
        }
        try_sorted:
        if (queryGoal.getOrdering() != null) {
            int idx = nequals;
            for (OrderByExpression targetColumn : queryGoal.getOrdering().getOrderBy()) {
                // Get the expression by which this is ordering, recognizing the
                // special cases where the Sort is fed by GROUP BY or feeds DISTINCT.
                ExpressionNode targetExpression = targetColumn.getExpression();
                if (targetExpression.isColumn()) {
                    ColumnExpression column = (ColumnExpression)targetExpression;
                    ColumnSource table = column.getTable();
                    if (table == queryGoal.getGrouping()) {
                        targetExpression = queryGoal.getGrouping()
                            .getField(column.getPosition());
                    }
                    else if (table instanceof Project) {
                        // Cf. ASTStatementLoader.sortsForDistinct().
                        Project project = (Project)table;
                        if ((project.getOutput() == queryGoal.getOrdering()) &&
                            (queryGoal.getOrdering().getOutput() instanceof Distinct)) {
                            targetExpression = project.getFields()
                                .get(column.getPosition());
                        }
                    }
                }
                OrderByExpression indexColumn = null;
                if (idx < indexOrdering.size()) {
                    indexColumn = indexOrdering.get(idx);
                    if (indexColumn.getExpression() == null)
                        indexColumn = null; // Index sorts by unknown column.
                }
                if ((indexColumn != null) && 
                    orderingExpressionMatches(indexColumn, targetExpression)) {
                    if (indexColumn.isAscending() != targetColumn.isAscending()) {
                        // To avoid mixed mode as much as possible,
                        // defer changing the index order until
                        // certain it will be effective.
                        reverse.set(idx, true);
                        if (idx == nequals)
                            // Likewise reverse the initial equals segment.
                            reverse.set(0, nequals, true);
                    }
                    if (idx >= index.getNKeyColumns())
                        index.setUsesAllColumns(true);
                    idx++;
                    continue;
                }
                if (equalityColumns != null) {
                    // Another possibility is that target ordering is
                    // in fact unchanged due to equality condition.
                    // TODO: Should this have been noticed earlier on
                    // so that it can be taken out of the sort?
                    if (equalityColumns.contains(targetExpression))
                        continue;
                }
                break try_sorted;
            }
            if ((idx > 0) && (idx < indexOrdering.size()) && reverse.get(idx-1))
                // Reverse after ORDER BY if reversed last one.
                reverse.set(idx, indexOrdering.size(), true);
            for (int i = 0; i < reverse.size(); i++) {
                if (reverse.get(i)) {
                    OrderByExpression indexColumn = indexOrdering.get(i);
                    indexColumn.setAscending(!indexColumn.isAscending());
                }
            }
            result = IndexScan.OrderEffectiveness.SORTED;
        }
        if (queryGoal.getGrouping() != null) {
            boolean anyFound = false, allFound = true;
            List<ExpressionNode> groupBy = queryGoal.getGrouping().getGroupBy();
            for (ExpressionNode targetExpression : groupBy) {
                int found = -1;
                for (int i = nequals; i < indexOrdering.size(); i++) {
                    if (orderingExpressionMatches(indexOrdering.get(i), targetExpression)) {
                        found = i - nequals;
                        break;
                    }
                }
                if (found < 0) {
                    allFound = false;
                    if ((equalityColumns == null) ||
                        !equalityColumns.contains(targetExpression))
                        continue;
                }
                else if (found >= groupBy.size()) {
                    // Ordered by this column, but after some other
                    // stuff which will break up the group. Only
                    // partially grouped.
                    allFound = false;
                }
                if (found >= index.getNKeyColumns()) {
                    index.setUsesAllColumns(true);
                }
                anyFound = true;
            }
            if (anyFound) {
                if (!allFound)
                    return IndexScan.OrderEffectiveness.PARTIAL_GROUPED;
                else if (result == IndexScan.OrderEffectiveness.SORTED)
                    return result;
                else
                    return IndexScan.OrderEffectiveness.GROUPED;
            }
        }
        else if (queryGoal.getProjectDistinct() != null) {
            assert (queryGoal.getOrdering() == null);
            if (orderedForDistinct(index, queryGoal.getProjectDistinct(), 
                                   indexOrdering, nequals)) {
                return IndexScan.OrderEffectiveness.SORTED;
            }
        }
        return result;
    }

    /** For use with a Distinct that gets added later. */
    public boolean orderedForDistinct(Project projectDistinct, IndexScan index) {
        List<OrderByExpression> indexOrdering = index.getOrdering();
        if (indexOrdering == null) return false;
        int nequals = index.getNEquality();
        return orderedForDistinct(index, projectDistinct, indexOrdering, nequals);
    }

    protected boolean orderedForDistinct(IndexScan index, Project projectDistinct, 
                                         List<OrderByExpression> indexOrdering,
                                         int nequals) {
        List<ExpressionNode> distinct = projectDistinct.getFields();
        for (ExpressionNode targetExpression : distinct) {
            int found = -1;
            for (int i = nequals; i < indexOrdering.size(); i++) {
                if (orderingExpressionMatches(indexOrdering.get(i), targetExpression)) {
                    found = i - nequals;
                    break;
                }
            }
            if ((found < 0) || (found >= distinct.size())) {
                return false;
            }
            if (found >= index.getNKeyColumns()) {
                index.setUsesAllColumns(true);
            }
        }
        return true;
    }

    // Does the column expression coming from the index match the ORDER BY target,
    // allowing for column equivalences?
    protected boolean orderingExpressionMatches(OrderByExpression orderByExpression,
                                                ExpressionNode targetExpression) {
        ExpressionNode columnExpression = orderByExpression.getExpression();
        if (columnExpression == null)
            return false;
        if (columnExpression.equals(targetExpression))
            return true;
        if (!(columnExpression instanceof ColumnExpression) ||
            !(targetExpression instanceof ColumnExpression))
            return false;
        return getColumnEquivalencies().areEquivalent((ColumnExpression)columnExpression,
                                                      (ColumnExpression)targetExpression);
    }

    protected EquivalenceFinder<ColumnExpression> getColumnEquivalencies() {
        return queryGoal.getQuery().getColumnEquivalencies();
    }

    protected class UnboundFinder implements ExpressionVisitor {
        boolean found = false;

        public UnboundFinder() {
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }
        @Override
        public boolean visitLeave(ExpressionNode n) {
            return !found;
        }
        @Override
        public boolean visit(ExpressionNode n) {
            if (n instanceof ColumnExpression) {
                ColumnExpression columnExpression = (ColumnExpression)n;
                if (!boundTables.contains(columnExpression.getTable())) {
                    found = true;
                    return false;
                }
            }
            else if (n instanceof SubqueryExpression) {
                for (ColumnSource used : ((SubqueryExpression)n).getSubquery().getOuterTables()) {
                    // Tables defined inside the subquery are okay, but ones from outside
                    // need to be bound to eval as an expression.
                    if (!boundTables.contains(used)) {
                        found = true;
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** Does the given expression have references to tables that aren't bound? */
    protected boolean constantOrBound(ExpressionNode expression) {
        UnboundFinder f = new UnboundFinder();
        expression.accept(f);
        return !f.found;
    }

    /** Get an expression form of the given index column. */
    protected static ExpressionNode getIndexExpression(IndexScan index,
                                                       IndexColumn indexColumn) {
        Column column = indexColumn.getColumn();
        UserTable indexTable = column.getUserTable();
        for (TableSource table = index.getLeafMostTable();
             null != table;
             table = table.getParentTable()) {
            if (table.getTable().getTable() == indexTable) {
                return new ColumnExpression(table, column);
            }
        }
        return null;
    }

    /** Is the comparison operand what the index indexes? */
    protected boolean indexExpressionMatches(ExpressionNode indexExpression,
                                             ExpressionNode comparisonOperand) {
        if (indexExpression.equals(comparisonOperand))
            return true;
        if (!(indexExpression instanceof ColumnExpression) ||
            !(comparisonOperand instanceof ColumnExpression))
            return false;
        if (getColumnEquivalencies().areEquivalent((ColumnExpression)indexExpression,
                                                   (ColumnExpression)comparisonOperand))
            return true;
        // See if comparing against a result column of the subquery,
        // that is, a join to the subquery that we can push down.
        ColumnExpression comparisonColumn = (ColumnExpression)comparisonOperand;
        ColumnSource comparisonTable = comparisonColumn.getTable();
        if (!(comparisonTable instanceof SubquerySource))
            return false;
        Subquery subquery = ((SubquerySource)comparisonTable).getSubquery();
        if (subquery != queryGoal.getQuery())
            return false;
        PlanNode input = subquery.getQuery();
        if (input instanceof ResultSet)
            input = ((ResultSet)input).getInput();
        if (!(input instanceof Project))
            return false;
        Project project = (Project)input;
        ExpressionNode insideExpression = project.getFields().get(comparisonColumn.getPosition());
        return indexExpressionMatches(indexExpression, insideExpression);
    }

    /** Find the best index among the branches. */
    public BaseScan pickBestScan() {
        Set<TableSource> required = tables.getRequired();
        BaseScan bestScan = null;

        if (tables.getGroup().getRejectedJoins() != null) {
            bestScan = pickBestGroupLoop();
        }

        IntersectionEnumerator intersections = new IntersectionEnumerator();
        for (TableGroupJoinNode table : tables) {
            IndexScan tableIndex = pickBestIndex(table, required, intersections);
            if ((tableIndex != null) &&
                ((bestScan == null) || (compare(tableIndex, bestScan) > 0)))
                bestScan = tableIndex;
        }
        bestScan = pickBestIntersection(bestScan, intersections);

        if (bestScan == null) {
            GroupScan groupScan = new GroupScan(tables.getGroup());
            groupScan.setCostEstimate(estimateCost(groupScan));
            bestScan = groupScan;
        }

        return bestScan;
    }

    private BaseScan pickBestIntersection(BaseScan previousBest, IntersectionEnumerator enumerator) {
        // filter out all leaves which are obviously bad
        if (previousBest != null) {
            CostEstimate previousBestCost = previousBest.getCostEstimate();
            for (Iterator<SingleIndexScan> iter = enumerator.leavesIterator(); iter.hasNext(); ) {
                SingleIndexScan scan = iter.next();
                CostEstimate scanCost = estimateIntersectionCost(scan);
                if (scanCost.compareTo(previousBestCost) > 0) {
                    logger.debug("Not intersecting {} {}", scan, scanCost);
                    iter.remove();
                }
            }
        }
        Function<? super IndexScan,Void> hook = intersectionEnumerationHook;
        for (Iterator<IndexScan> iterator = enumerator.iterator(); iterator.hasNext(); ) {
            IndexScan intersectedIndex = iterator.next();
            if (hook != null)
                hook.apply(intersectedIndex);
            setIntersectionConditions(intersectedIndex);
            intersectedIndex.setCovering(determineCovering(intersectedIndex));
            intersectedIndex.setCostEstimate(estimateCost(intersectedIndex));
            if (previousBest == null) {
                logger.debug("Selecting {}", intersectedIndex);
                previousBest = intersectedIndex;
            }
            else if (compare(intersectedIndex, previousBest) > 0) {
                logger.debug("Preferring {}", intersectedIndex);
                previousBest = intersectedIndex;

            }
            else {
                logger.debug("Rejecting {}", intersectedIndex);
                // If the scan costs alone are higher than the previous best cost, there's no way this scan or
                // any scan that uses it will be the best. Just remove the whole branch.
                if (intersectedIndex.getScanCostEstimate().compareTo(previousBest.getCostEstimate()) > 0)
                    iterator.remove();
            }
        }
        return previousBest;
    }

    private void setIntersectionConditions(IndexScan rawScan) {
        MultiIndexIntersectScan scan = (MultiIndexIntersectScan) rawScan;

        if (isAncestor(scan.getOutputIndexScan().getLeafMostTable(),
                       scan.getSelectorIndexScan().getLeafMostTable())) {
            // More conditions up the same branch are safely implied by the output row.
            ConditionsCounter<ConditionExpression> counter = new ConditionsCounter<ConditionExpression>(conditions.size());
            scan.incrementConditionsCounter(counter);
            scan.setConditions(new ArrayList<ConditionExpression>(counter.getCountedConditions()));
        }
        else {
            // Otherwise only those for the output row are safe and
            // conditions on another branch need to be checked again;
            scan.setConditions(scan.getOutputIndexScan().getConditions());
        }
    }

    /** Is the given <code>rootTable</code> an ancestor of <code>leafTable</code>? */
    private static boolean isAncestor(TableSource leafTable, TableSource rootTable) {
        do {
            if (leafTable == rootTable)
                return true;
            leafTable = leafTable.getParentTable();
        } while (leafTable != null);
        return false;
    }
    
    private class IntersectionEnumerator extends MultiIndexEnumerator<ConditionExpression,IndexScan,SingleIndexScan> {

        @Override
        protected Collection<ConditionExpression> getLeafConditions(SingleIndexScan scan) {
            int skips = scan.getPeggedCount();
            List<ConditionExpression> conditions = scan.getConditions();
            if (conditions == null)
                return null;
            int nconds = conditions.size();
            return ((skips) > 0 && (skips == nconds)) ? conditions : null;
        }

        @Override
        protected IndexScan intersect(IndexScan first, IndexScan second, int comparisons) {
            return new MultiIndexIntersectScan(first, second, comparisons);
        }

        @Override
        protected List<Column> getComparisonColumns(IndexScan first, IndexScan second) {
            EquivalenceFinder<ColumnExpression> equivs = getColumnEquivalencies();
            List<ExpressionNode> firstOrdering = orderingCols(first);
            List<ExpressionNode> secondOrdering = orderingCols(second);
            int ncols = Math.min(firstOrdering.size(), secondOrdering.size());
            List<Column> result = new ArrayList<Column>(ncols);
            for (int i=0; i < ncols; ++i) {
                ExpressionNode firstCol = firstOrdering.get(i);
                ExpressionNode secondCol = secondOrdering.get(i);
                if ((firstCol == null) || (secondCol == null))
                    break;
                if ((!(firstCol instanceof ColumnExpression) || !(secondCol instanceof ColumnExpression)) &&
                    !firstCol.equals(secondCol))
                    break;
                if (!equivs.areEquivalent((ColumnExpression) firstCol, (ColumnExpression) secondCol))
                    break;
                result.add(((ColumnExpression)firstCol).getColumn());
            }
            return result;
        }

        private List<ExpressionNode> orderingCols(IndexScan index) {
            List<ExpressionNode> result = index.getColumns();
            return result.subList(index.getPeggedCount(), result.size());
        }
    }

    /** Find the best index on the given table. 
     * @param required Tables reachable from root via INNER joins and hence not nullable.
     */
    public IndexScan pickBestIndex(TableGroupJoinNode node, Set<TableSource> required, IntersectionEnumerator enumerator) {
        TableSource table = node.getTable();
        IndexScan bestIndex = null;
        // Can only consider single table indexes when table is not
        // nullable (required).  If table is the optional part of a
        // LEFT join, can still consider compatible LEFT / RIGHT group
        // indexes, below. WHERE conditions are removed before this is
        // called, see GroupIndexGoal#setJoinConditions().
        if (required.contains(table)) {
            for (TableIndex index : table.getTable().getTable().getIndexes()) {
                SingleIndexScan candidate = new SingleIndexScan(index, table);
                bestIndex = betterIndex(bestIndex, candidate, enumerator);
            }
        }
        if ((table.getGroup() != null) && !hasOuterJoinNonGroupConditions(node)) {
            for (GroupIndex index : table.getGroup().getGroup().getIndexes()) {
                // The leaf must be used or else we'll get duplicates from a
                // scan (the indexed columns need not be root to leaf, making
                // ancestors discontiguous and duplicates hard to eliminate).
                if (index.leafMostTable() != table.getTable().getTable())
                    continue;
                TableSource rootTable = table;
                TableSource rootRequired = null, leafRequired = null;
                if (index.getJoinType() == JoinType.LEFT) {
                    while (rootTable != null) {
                        if (required.contains(rootTable)) {
                            rootRequired = rootTable;
                            if (leafRequired == null)
                                leafRequired = rootTable;
                        }
                        else {
                            if (leafRequired != null) {
                                // Optional above required, not LEFT join compatible.
                                leafRequired = null;
                                break;
                            }
                        }
                        if (index.rootMostTable() == rootTable.getTable().getTable())
                            break;
                        rootTable = rootTable.getParentTable();
                    }
                    // The root must be present, since a LEFT index
                    // does not contain orphans.
                    if ((rootTable == null) || 
                        (rootRequired != rootTable) ||
                        (leafRequired == null))
                        continue;
                }
                else {
                    if (!required.contains(table))
                        continue;
                    leafRequired = table;
                    boolean optionalSeen = false;
                    while (rootTable != null) {
                        if (required.contains(rootTable)) {
                            if (optionalSeen) {
                                // Required above optional, not RIGHT join compatible.
                                rootRequired = null;
                                break;
                            }
                            rootRequired = rootTable;
                        }
                        else {
                            optionalSeen = true;
                        }
                        if (index.rootMostTable() == rootTable.getTable().getTable())
                            break;
                        rootTable = rootTable.getParentTable();
                    }
                    // TODO: There are no INNER JOIN group indexes,
                    // but this would support them.
                    /*
                    if (optionalSeen && (index.getJoinType() == JoinType.INNER))
                        continue;
                    */
                    if ((rootTable == null) ||
                        (rootRequired == null))
                        continue;
                }
                SingleIndexScan candidate = new SingleIndexScan(index, rootTable,
                                                    rootRequired, leafRequired, 
                                                    table);
                bestIndex = betterIndex(bestIndex, candidate, enumerator);
            }
        }
        return bestIndex;
    }

    // If a LEFT join has more conditions, they won't be included in an index, so
    // can't use it.
    protected boolean hasOuterJoinNonGroupConditions(TableGroupJoinNode node) {
        if (node.getTable().isRequired())
            return false;
        ConditionList conditions = node.getJoinConditions();
        if (conditions != null) {
            for (ConditionExpression cond : conditions) {
                if (cond.getImplementation() != ConditionExpression.Implementation.GROUP_JOIN) {
                    return true;
                }
            }
        }
        return false;
    }

    protected IndexScan betterIndex(IndexScan bestIndex, SingleIndexScan candidate, IntersectionEnumerator enumerator) {
        if (usable(candidate)) {
            enumerator.addLeaf(candidate);
            if (bestIndex == null) {
                logger.debug("Selecting {}", candidate);
                return candidate;
            }
            else if (compare(candidate, bestIndex) > 0) {
                logger.debug("Preferring {}", candidate);
                return candidate;
            }
            else {
                logger.debug("Rejecting {}", candidate);
            }
        }
        return bestIndex;
    }

    private GroupLoopScan pickBestGroupLoop() {
        GroupLoopScan bestScan = null;
        
        Set<TableSource> outsideSameGroup = new HashSet<TableSource>(tables.getGroup().getTables());
        outsideSameGroup.retainAll(boundTables);

        for (TableGroupJoin join : tables.getGroup().getRejectedJoins()) {
            TableSource parent = join.getParent();
            TableSource child = join.getChild();
            TableSource inside, outside;
            boolean insideIsParent;
            if (outsideSameGroup.contains(parent) && tables.containsTable(child)) {
                inside = child;
                outside = parent;
                insideIsParent = false;
            }
            else if (outsideSameGroup.contains(child) && tables.containsTable(parent)) {
                inside = parent;
                outside = child;
                insideIsParent = true;
            }
            else {
                continue;
            }
            if (mightFlatten(outside)) {
                continue;       // Lookup_Nested won't be allowed.
            }
            GroupLoopScan forJoin = new GroupLoopScan(inside, outside, insideIsParent,
                                                      join.getConditions());
            determineRequiredTables(forJoin);
            forJoin.setCostEstimate(estimateCost(forJoin));
            if (bestScan == null) {
                logger.debug("Selecting {}", forJoin);
                bestScan = forJoin;
            }
            else if (compare(forJoin, bestScan) > 0) {
                logger.debug("Preferring {}", forJoin);
                bestScan = forJoin;
            }
            else {
                logger.debug("Rejecting {}", forJoin);
            }
        }

        return bestScan;
    }

    private boolean mightFlatten(TableSource table) {
        if (!(table.getOutput() instanceof TableGroupJoinTree))
            return true;        // Don't know; be conservative.
        TableGroupJoinTree tree = (TableGroupJoinTree)table.getOutput();
        TableGroupJoinNode root = tree.getRoot();
        if (root.getTable() != table)
            return true;
        if (root.getFirstChild() != null)
            return true;
        return false;           // Only table in this join tree, shouldn't flatten.
    }

    public int compare(BaseScan i1, BaseScan i2) {
        return i2.getCostEstimate().compareTo(i1.getCostEstimate());
    }

    protected boolean determineCovering(IndexScan index) {
        // Include the non-condition requirements.
        RequiredColumns requiredAfter = new RequiredColumns(requiredColumns);
        RequiredColumnsFiller filler = new RequiredColumnsFiller(requiredAfter);
        // Add in any conditions not handled by the index.
        for (ConditionExpression condition : conditions) {
            boolean found = false;
            if (index.getConditions() != null) {
                for (ConditionExpression indexCondition : index.getConditions()) {
                    if (indexCondition == condition) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found)
                condition.accept(filler);
        }
        // Add sort if not handled by the index.
        if ((queryGoal.getOrdering() != null) &&
            (index.getOrderEffectiveness() != IndexScan.OrderEffectiveness.SORTED)) {
            // Only this node, not its inputs.
            filler.setIncludedPlanNodes(Collections.<PlanNode>singletonList(queryGoal.getOrdering()));
            queryGoal.getOrdering().accept(filler);
        }
            
        // Record what tables are required: within the index if any
        // columns still needed, others if joined at all. Do this
        // before taking account of columns from a covering index,
        // since may not use it that way.
        {
            Collection<TableSource> joined = index.getTables();
            Set<TableSource> required = new HashSet<TableSource>();
            boolean moreTables = false;
            for (TableSource table : requiredAfter.getTables()) {
                if (!joined.contains(table)) {
                    moreTables = true;
                    required.add(table);
                }
                else if (requiredAfter.hasColumns(table) ||
                         (table.getTable() == queryGoal.getUpdateTarget())) {
                    required.add(table);
                }
            }
            index.setRequiredTables(required);
            if (moreTables)
                // Need to join up last the index; index might point
                // to an orphan.
                return false;
        }

        if (queryGoal.getUpdateTarget() != null) {
          // UPDATE statements need the whole target row and are thus never covering.
          return false;
        }

        // Remove the columns we do have from the index.
        int ncols = index.getColumns().size();
        for (int i = 0; i < ncols; i++) {
            ExpressionNode column = index.getColumns().get(i);
            if ((column instanceof ColumnExpression) && index.isRecoverableAt(i)) {
                if (requiredAfter.have((ColumnExpression)column) &&
                    (i >= index.getNKeyColumns())) {
                    index.setUsesAllColumns(true);
                }
            }
        }
        return requiredAfter.isEmpty();
    }

    protected void determineRequiredTables(GroupLoopScan scan) {
        // Include the non-condition requirements.
        RequiredColumns requiredAfter = new RequiredColumns(requiredColumns);
        RequiredColumnsFiller filler = new RequiredColumnsFiller(requiredAfter);
        // Add in any non-join conditions.
        for (ConditionExpression condition : conditions) {
            boolean found = false;
            for (ConditionExpression joinCondition : scan.getJoinConditions()) {
                if (joinCondition == condition) {
                    found = true;
                    break;
                }
            }
            if (!found)
                condition.accept(filler);
        }
        // Does not sort.
        if (queryGoal.getOrdering() != null) {
            // Only this node, not its inputs.
            filler.setIncludedPlanNodes(Collections.<PlanNode>singletonList(queryGoal.getOrdering()));
            queryGoal.getOrdering().accept(filler);
        }
        // The only table we can exclude is the one initially joined to, in the case
        // where all the data comes from elsewhere on that branch.
        Set<TableSource> required = new HashSet<TableSource>(requiredAfter.getTables());
        if (!requiredAfter.hasColumns(scan.getInsideTable()))
            required.remove(scan.getInsideTable());
        scan.setRequiredTables(required);
    }

    public CostEstimate estimateCost(IndexScan index) {
        return estimateCost(index, queryGoal.getLimit());
    }

    public CostEstimate estimateCost(IndexScan index, long limit) {
        PlanCostEstimator estimator = 
            new PlanCostEstimator(queryGoal.getCostEstimator());
        Set<TableSource> requiredTables = index.getRequiredTables();

        estimator.indexScan(index);

        if (!index.isCovering()) {
            estimator.flatten(tables, 
                              index.getLeafMostTable(), requiredTables);
        }

        Collection<ConditionExpression> unhandledConditions = 
            new HashSet<ConditionExpression>(conditions);
        if (index.getConditions() != null)
            unhandledConditions.removeAll(index.getConditions());
        if (!unhandledConditions.isEmpty()) {
            estimator.select(unhandledConditions,
                             selectivityConditions(unhandledConditions, requiredTables));
        }

        if (queryGoal.needSort(index.getOrderEffectiveness())) {
            estimator.sort(queryGoal.sortFields());
        }

        estimator.setLimit(limit);

        return estimator.getCostEstimate();
    }

    public CostEstimate estimateIntersectionCost(IndexScan index) {
        if (index.getOrderEffectiveness() == IndexScan.OrderEffectiveness.NONE)
            return index.getScanCostEstimate();
        long limit = queryGoal.getLimit();
        if (limit < 0)
            return index.getScanCostEstimate();
        // There is a limit and this index looks to be sorted, so adjust for that
        // limit. Otherwise, the scan only cost, which includes all rows, will appear
        // too large compared to a limit-aware best plan.
        PlanCostEstimator estimator = 
            new PlanCostEstimator(queryGoal.getCostEstimator());
        estimator.indexScan(index);
        estimator.setLimit(limit);
        return estimator.getCostEstimate();
    }

    public CostEstimate estimateCost(GroupScan scan) {
        PlanCostEstimator estimator = 
            new PlanCostEstimator(queryGoal.getCostEstimator());
        Set<TableSource> requiredTables = requiredColumns.getTables();

        estimator.groupScan(scan, tables, requiredTables);

        if (!conditions.isEmpty()) {
            estimator.select(conditions,
                             selectivityConditions(conditions, requiredTables));
        }
        
        estimator.setLimit(queryGoal.getLimit());

        return estimator.getCostEstimate();
    }

    public CostEstimate estimateCost(GroupLoopScan scan) {
        PlanCostEstimator estimator = 
            new PlanCostEstimator(queryGoal.getCostEstimator());
        Set<TableSource> requiredTables = scan.getRequiredTables();

        estimator.groupLoop(scan, tables, requiredTables);

        Collection<ConditionExpression> unhandledConditions = 
            new HashSet<ConditionExpression>(conditions);
        unhandledConditions.removeAll(scan.getJoinConditions());
        if (!unhandledConditions.isEmpty()) {
            estimator.select(unhandledConditions,
                             selectivityConditions(unhandledConditions, requiredTables));
        }

        if (queryGoal.needSort(IndexScan.OrderEffectiveness.NONE)) {
            estimator.sort(queryGoal.sortFields());
        }

        estimator.setLimit(queryGoal.getLimit());

        return estimator.getCostEstimate();
    }

    public double estimateSelectivity(IndexScan index) {
        return queryGoal.getCostEstimator().conditionsSelectivity(selectivityConditions(index.getConditions(), index.getTables()));
    }

    // Conditions that might have a recognizable selectivity.
    protected Map<ColumnExpression,Collection<ComparisonCondition>> selectivityConditions(Collection<ConditionExpression> conditions, Collection<TableSource> requiredTables) {
        Map<ColumnExpression,Collection<ComparisonCondition>> result = new
            HashMap<ColumnExpression,Collection<ComparisonCondition>>();
        for (ConditionExpression condition : conditions) {
            if (condition instanceof ComparisonCondition) {
                ComparisonCondition ccond = (ComparisonCondition)condition;
                if (ccond.getLeft() instanceof ColumnExpression) {
                    ColumnExpression column = (ColumnExpression)ccond.getLeft();
                    if ((column.getColumn() != null) &&
                        requiredTables.contains(column.getTable()) &&
                        constantOrBound(ccond.getRight())) {
                        Collection<ComparisonCondition> entry = result.get(column);
                        if (entry == null) {
                            entry = new ArrayList<ComparisonCondition>();
                            result.put(column, entry);
                        }
                        entry.add(ccond);
                    }
                }
            }
        }
        return result;
    }

    // Recognize the case of a join that is only used for predication.
    // TODO: This is only covers the simplest case, namely an index that is unique
    // none of whose columns are actually used.
    public boolean semiJoinEquivalent(BaseScan scan) {
        if (scan instanceof SingleIndexScan) {
            SingleIndexScan indexScan = (SingleIndexScan)scan;
            if (indexScan.isCovering() && isUnique(indexScan) && 
                requiredColumns.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Does this scan return at most one row?
    protected boolean isUnique(SingleIndexScan indexScan) {
        List<ExpressionNode> equalityComparands = indexScan.getEqualityComparands();
        if (equalityComparands == null)
            return false;
        int nequals = equalityComparands.size();
        Index index = indexScan.getIndex();
        if (index.isUnique() && (nequals >= index.getKeyColumns().size()))
            return true;
        if (index.isGroupIndex())
            return false;
        Set<Column> equalityColumns = new HashSet<Column>(nequals);
        for (int i = 0; i < nequals; i++) {
            ExpressionNode equalityExpr = indexScan.getColumns().get(i);
            if (equalityExpr instanceof ColumnExpression) {
                equalityColumns.add(((ColumnExpression)equalityExpr).getColumn());
            }
        }
        TableIndex tableIndex = (TableIndex)index;
        find_index:             // Find a unique index all of whose columns are equaled.
        for (TableIndex otherIndex : tableIndex.getTable().getIndexes()) {
            if (!otherIndex.isUnique()) continue;
            for (IndexColumn otherColumn : otherIndex.getKeyColumns()) {
                if (!equalityColumns.contains(otherColumn.getColumn()))
                    continue find_index;
            }
            return true;
        }
        return false;
    }

    public TableGroupJoinTree install(BaseScan scan,
                                      List<ConditionList> conditionSources,
                                      boolean sortAllowed, boolean copy) {
        TableGroupJoinTree result = tables;
        // Need to have more than one copy of this tree in the final result.
        if (copy) result = new TableGroupJoinTree(result.getRoot());
        result.setScan(scan);
        this.sortAllowed = sortAllowed;
        if (scan instanceof IndexScan) {
            IndexScan indexScan = (IndexScan)scan;
            if (indexScan instanceof MultiIndexIntersectScan) {
                MultiIndexIntersectScan multiScan = (MultiIndexIntersectScan)indexScan;
                installOrdering(indexScan, multiScan.getOrdering(), multiScan.getPeggedCount(), multiScan.getComparisonFields());
            }
            installConditions(indexScan.getConditions(), conditionSources);
            if (sortAllowed)
                queryGoal.installOrderEffectiveness(indexScan.getOrderEffectiveness());
        }
        else {
            if (scan instanceof GroupLoopScan) {
                installConditions(((GroupLoopScan)scan).getJoinConditions(), 
                                  conditionSources);
            }
            if (sortAllowed)
                queryGoal.installOrderEffectiveness(IndexScan.OrderEffectiveness.NONE);
        }
        return result;
    }

    /** Change WHERE as a consequence of <code>index</code> being
     * used, using either the sources returned by {@link updateContext} or the
     * current ones if nothing has been changed.
     */
    public void installConditions(Collection<? extends ConditionExpression> conditions,
                                  List<ConditionList> conditionSources) {
        if (conditions != null) {
            if (conditionSources == null)
                conditionSources = this.conditionSources;
            for (ConditionExpression condition : conditions) {
                for (ConditionList conditionSource : conditionSources) {
                    if (conditionSource.remove(condition))
                        break;
                }
            }
        }
    }

    // Get Range-expressible conditions for given column.
    protected ColumnRanges rangeForIndex(ExpressionNode expressionNode) {
        if (expressionNode instanceof ColumnExpression) {
            if (columnsToRanges == null) {
                columnsToRanges = new HashMap<ColumnExpression, ColumnRanges>();
                for (ConditionExpression condition : conditions) {
                    ColumnRanges range = ColumnRanges.rangeAtNode(condition);
                    if (range != null) {
                        ColumnExpression rangeColumn = range.getColumnExpression();
                        ColumnRanges oldRange = columnsToRanges.get(rangeColumn);
                        if (oldRange != null)
                            range = ColumnRanges.andRanges(range, oldRange);
                        columnsToRanges.put(rangeColumn, range);
                    }
                }
            }
            ColumnExpression columnExpression = (ColumnExpression)expressionNode;
            return columnsToRanges.get(columnExpression);
        }
        return null;
    }
    
    static class RequiredColumns {
        private Map<TableSource,Set<ColumnExpression>> map;
        
        public RequiredColumns(TableGroupJoinTree tables) {
            map = new HashMap<TableSource,Set<ColumnExpression>>();
            for (TableGroupJoinNode table : tables) {
                map.put(table.getTable(), new HashSet<ColumnExpression>());
            }
        }

        public RequiredColumns(RequiredColumns other) {
            map = new HashMap<TableSource,Set<ColumnExpression>>(other.map.size());
            for (Map.Entry<TableSource,Set<ColumnExpression>> entry : other.map.entrySet()) {
                map.put(entry.getKey(), new HashSet<ColumnExpression>(entry.getValue()));
            }
        }

        public Set<TableSource> getTables() {
            return map.keySet();
        }
        
        public boolean hasColumns(TableSource table) {
            Set<ColumnExpression> entry = map.get(table);
            if (entry == null) return false;
            return !entry.isEmpty();
        }

        public boolean isEmpty() {
            boolean empty = true;
            for (Set<ColumnExpression> entry : map.values())
                if (!entry.isEmpty())
                    return false;
            return empty;
        }

        public void require(ColumnExpression expr) {
            Set<ColumnExpression> entry = map.get(expr.getTable());
            if (entry != null)
                entry.add(expr);
        }

        /** Opposite of {@link require}: note that we have a source for this column. */
        public boolean have(ColumnExpression expr) {
            Set<ColumnExpression> entry = map.get(expr.getTable());
            if (entry != null)
                return entry.remove(expr);
            else
                return false;
        }

        public void clear() {
            for (Set<ColumnExpression> entry : map.values())
                entry.clear();
        }
    }

    static class RequiredColumnsFiller implements PlanVisitor, ExpressionVisitor {
        private RequiredColumns requiredColumns;
        private Map<PlanNode,Void> excludedPlanNodes, includedPlanNodes;
        private Map<ExpressionNode,Void> excludedExpressions;
        private Deque<Boolean> excludeNodeStack = new ArrayDeque<Boolean>();
        private boolean excludeNode = false;
        private int excludeDepth = 0;
        private int subqueryDepth = 0;

        public RequiredColumnsFiller(RequiredColumns requiredColumns) {
            this.requiredColumns = requiredColumns;
        }

        public RequiredColumnsFiller(RequiredColumns requiredColumns,
                                     Collection<PlanNode> excludedPlanNodes,
                                     Collection<ConditionExpression> excludedExpressions) {
            this.requiredColumns = requiredColumns;
            this.excludedPlanNodes = new IdentityHashMap<PlanNode,Void>();
            for (PlanNode planNode : excludedPlanNodes)
                this.excludedPlanNodes.put(planNode, null);
            this.excludedExpressions = new IdentityHashMap<ExpressionNode,Void>();
            for (ConditionExpression condition : excludedExpressions)
                this.excludedExpressions.put(condition, null);
        }

        public void setIncludedPlanNodes(Collection<PlanNode> includedPlanNodes) {
            this.includedPlanNodes = new IdentityHashMap<PlanNode,Void>();
            for (PlanNode planNode : includedPlanNodes)
                this.includedPlanNodes.put(planNode, null);
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            // Input nodes are called within the context of their output.
            // We want to know whether just this node is excluded, not
            // it and all its inputs.
            excludeNodeStack.push(excludeNode);
            excludeNode = exclude(n);
            if ((n instanceof Subquery) &&
                !((Subquery)n).getOuterTables().isEmpty())
                // TODO: Might be accessing tables from outer query as
                // group joins, which we don't support currently. Make
                // sure those aren't excluded.
                subqueryDepth++;
            return visit(n);
        }
        @Override
        public boolean visitLeave(PlanNode n) {
            excludeNode = excludeNodeStack.pop();
            if ((n instanceof Subquery) &&
                !((Subquery)n).getOuterTables().isEmpty())
                subqueryDepth--;
            return true;
        }
        @Override
        public boolean visit(PlanNode n) {
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            if (!excludeNode && exclude(n))
                excludeDepth++;
            return visit(n);
        }
        @Override
        public boolean visitLeave(ExpressionNode n) {
            if (!excludeNode && exclude(n))
                excludeDepth--;
            return true;
        }
        @Override
        public boolean visit(ExpressionNode n) {
            if (!excludeNode && (excludeDepth == 0)) {
                if (n instanceof ColumnExpression)
                    requiredColumns.require((ColumnExpression)n);
            }
            return true;
        }

        // Should this plan node be excluded from the requirement?
        protected boolean exclude(PlanNode node) {
            if (includedPlanNodes != null)
                return !includedPlanNodes.containsKey(node);
            else if (excludedPlanNodes != null)
                return excludedPlanNodes.containsKey(node);
            else
                return false;
        }
        
        // Should this expression be excluded from requirement?
        protected boolean exclude(ExpressionNode expr) {
            return (((excludedExpressions != null) &&
                     excludedExpressions.containsKey(expr)) ||
                    // Group join conditions are handled specially.
                    ((expr instanceof ConditionExpression) &&
                     (((ConditionExpression)expr).getImplementation() ==
                      ConditionExpression.Implementation.GROUP_JOIN) &&
                     // Include expressions in subqueries until do joins across them.
                     (subqueryDepth == 0)));
        }
    }

    /* Spatial indexes */

    /** For now, a spatial index is a special kind of table index on
     * Z-order of two coordinates.
     */
    public boolean spatialUsable(SingleIndexScan index, int nequals) {
        // There are two cases to recognize:
        // ORDER BY znear(column_lat, column_lon, start_lat, start_lon), which
        // means fan out from that center in Z-order.
        // WHERE distance_lat_lon(column_lat, column_lon, start_lat, start_lon) <= radius
        
        ExpressionNode nextColumn = index.getColumns().get(nequals);
        if (!(nextColumn instanceof SpecialIndexExpression)) 
            return false;       // Did not have enough equalities to get to spatial part.
        SpecialIndexExpression indexExpression = (SpecialIndexExpression)nextColumn;
        assert (indexExpression.getFunction() == SpecialIndexExpression.Function.Z_ORDER_LAT_LON);
        List<ExpressionNode> operands = indexExpression.getOperands();

        boolean matched = false;
        for (ConditionExpression condition : conditions) {
            if (condition instanceof ComparisonCondition) {
                ComparisonCondition ccond = (ComparisonCondition)condition;
                ExpressionNode centerRadius = null;
                switch (ccond.getOperation()) {
                case LE:
                case LT:
                    centerRadius = matchDistanceLatLon(operands,
                                                       ccond.getLeft(), 
                                                       ccond.getRight());
                    break;
                case GE:
                case GT:
                    centerRadius = matchDistanceLatLon(operands,
                                                       ccond.getRight(), 
                                                       ccond.getLeft());
                    break;
                }
                if (centerRadius != null) {
                    index.setLowComparand(centerRadius, true);
                    index.setOrderEffectiveness(IndexScan.OrderEffectiveness.NONE);
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            if (sortAllowed && (queryGoal.getOrdering() != null)) {
                List<OrderByExpression> orderBy = queryGoal.getOrdering().getOrderBy();
                if (orderBy.size() == 1) {
                    ExpressionNode center = matchZnear(operands,
                                                       orderBy.get(0));
                    if (center != null) {
                        index.setLowComparand(center, true);
                        index.setOrderEffectiveness(IndexScan.OrderEffectiveness.SORTED);
                        matched = true;
                    }
                }
            }
            if (!matched)
                return false;
        }

        index.setCovering(determineCovering(index));
        index.setCostEstimate(estimateCostSpatial(index));
        return true;
    }

    private ExpressionNode matchDistanceLatLon(List<ExpressionNode> indexExpressions,
                                               ExpressionNode left, ExpressionNode right) {
        if (!((left instanceof FunctionExpression) &&
              ((FunctionExpression)left).getFunction().equalsIgnoreCase("distance_lat_lon") &&
              constantOrBound(right)))
            return null;
        ExpressionNode col1 = indexExpressions.get(0);
        ExpressionNode col2 = indexExpressions.get(1);
        List<ExpressionNode> operands = ((FunctionExpression)left).getOperands();
        if (operands.size() != 4) return null; // TODO: Would error here be better?
        ExpressionNode op1 = operands.get(0);
        ExpressionNode op2 = operands.get(1);
        ExpressionNode op3 = operands.get(2);
        ExpressionNode op4 = operands.get(3);
        if (right.getAkType() != AkType.DECIMAL) {
            DataTypeDescriptor sqlType = 
                new DataTypeDescriptor(TypeId.DECIMAL_ID, 10, 6, true, 12);
            right = new CastExpression(right, sqlType, right.getSQLsource());
            if (Types3Switch.ON) {
                TInstance instance = TypesTranslation.toTInstance(sqlType);
                right.setPreptimeValue(new TPreptimeValue(instance));
            }
        }
        if (columnMatches(col1, op1) && columnMatches(col2, op2) &&
            constantOrBound(op3) && constantOrBound(op4)) {
            return new FunctionExpression("_center_radius",
                                          Arrays.asList(op3, op4, right),
                                          null, null);
        }
        if (columnMatches(col1, op3) && columnMatches(col2, op4) &&
            constantOrBound(op1) && constantOrBound(op2)) {
            return new FunctionExpression("_center_radius",
                                          Arrays.asList(op1, op2, right),
                                          null, null);
        }
        return null;
    }

    private ExpressionNode matchZnear(List<ExpressionNode> indexExpressions, 
                                      OrderByExpression orderBy) {
        if (!orderBy.isAscending()) return null;
        ExpressionNode orderExpr = orderBy.getExpression();
        if (!((orderExpr instanceof FunctionExpression) &&
              ((FunctionExpression)orderExpr).getFunction().equalsIgnoreCase("znear")))
            return null;
        ExpressionNode col1 = indexExpressions.get(0);
        ExpressionNode col2 = indexExpressions.get(1);
        List<ExpressionNode> operands = ((FunctionExpression)orderExpr).getOperands();
        if (operands.size() != 4) return null; // TODO: Would error here be better?
        ExpressionNode op1 = operands.get(0);
        ExpressionNode op2 = operands.get(1);
        ExpressionNode op3 = operands.get(2);
        ExpressionNode op4 = operands.get(3);
        if (columnMatches(col1, op1) && columnMatches(col2, op2) &&
            constantOrBound(op3) && constantOrBound(op4))
            return new FunctionExpression("_center",
                                          Arrays.asList(op3, op4),
                                          null, null);
        if (columnMatches(col1, op3) && columnMatches(col2, op4) &&
            constantOrBound(op1) && constantOrBound(op2))
            return new FunctionExpression("_center",
                                          Arrays.asList(op1, op2),
                                          null, null);
        return null;
    }

    private static boolean columnMatches(ExpressionNode col, ExpressionNode op) {
        if (op instanceof CastExpression)
            op = ((CastExpression)op).getOperand();
        return col.equals(op);
    }

    public CostEstimate estimateCostSpatial(SingleIndexScan index) {
        PlanCostEstimator estimator = 
            new PlanCostEstimator(queryGoal.getCostEstimator());
        Set<TableSource> requiredTables = requiredColumns.getTables();

        estimator.spatialIndex(index);

        if (!index.isCovering()) {
            estimator.flatten(tables, index.getLeafMostTable(), requiredTables);
        }

        Collection<ConditionExpression> unhandledConditions = new HashSet<ConditionExpression>(conditions);
        if (index.getConditions() != null)
            unhandledConditions.removeAll(index.getConditions());
        if (!unhandledConditions.isEmpty()) {
            estimator.select(unhandledConditions,
                             selectivityConditions(unhandledConditions, requiredTables));
        }

        if (queryGoal.needSort(index.getOrderEffectiveness())) {
            estimator.sort(queryGoal.sortFields());
        }

        estimator.setLimit(queryGoal.getLimit());

        return estimator.getCostEstimate();
    }

}
