import operator
import db.map

Operator = operator.Operator
SORT = operator.SORT
Map = db.map.Map

class Sort(Operator):

    def __init__(self, input, rowtype, sort_key):
        Operator.__init__(self)
        self._input = input
        self._rowtype = rowtype
        self._sort_key = sort_key
        self._sorted = None
        self._stats[SORT] = 0

    def open(self):
        self._input.open()

    def next(self):
        if self._sorted is None:
            rows = []
            row = self._input.next()
            while row:
                assert row.rowtype is self._rowtype
                rows.append(row)
                row = self._input.next()
            rows.sort(key = lambda row: self._sort_key(row))
            self._sorted = iter(rows)
            self._stats[SORT] += len(rows)
        try:
            output_row = self._sorted.next()
        except StopIteration:
            output_row = None
        return output_row

    def close(self):
        self._input.close()

    def stats(self):
        return self._stats.merge(self._input.stats())
