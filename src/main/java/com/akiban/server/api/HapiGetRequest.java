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

package com.akiban.server.api;

import com.akiban.ais.model.TableName;

import java.util.List;

public interface HapiGetRequest {
    String getSchema();

    String getTable();

    TableName getUsingTable();

    List<Predicate> getPredicates();

    interface Predicate {
        TableName getTableName();

        String getColumnName();

        Operator getOp();

        String getValue();

        StringBuilder appendToSB(StringBuilder builder, TableName usingTable);

        public enum Operator {
            EQ("=="),
            NE("!="),
            GT(">"),
            GTE(">="),
            LT("<"),
            LTE("<=")
            ;

            final private String toString;
            Operator(String toString) {
                this.toString = toString;
            }

            @Override
            public String toString() {
                return toString;
            }
        }
    }
}