package hypermake.collection

import scala.collection._

class CaseCube(val underlying: Map[Name, Set[String]]) { self =>

  def filterVars(p: Name => Boolean) = CaseCube {
    underlying.view.filterKeys(p).toMap
  }

  def vars = underlying.keySet

  def assignments: Iterable[(Name, Set[String])] = underlying

  def apply(a: Name) = underlying(a)

  def containsAxis(a: Name) = underlying contains a

  def containsCase(c: Case) = c.assignments.forall { case (a, k) =>
    !(underlying contains a) || (underlying(a) contains k)
  }

  def normalizeCase(c: Case): Case = Case(
    c.underlying.filter { case (a, _) => vars contains a }
  )

  /**
   * Selects the subcube of cases once variables are bound by the given case.
   */
  def select(c: Case) = filterVars(a => !c.contains(a))

  /**
   * Selects the subcube of cases given variables take the given values in the specified case cube.
   */
  def selectMany(cc: CaseCube) = CaseCube {
    self.underlying.map { case (a, ks) =>
      if (cc containsAxis a)
        a -> (ks intersect cc(a))
      else a -> ks
    }
  }

  def outerJoin(that: CaseCube) = CaseCube {
    val newVars = self.vars union that.vars
    newVars.view.map {
      case a if self.containsAxis(a) && that.containsAxis(a) =>
        a -> (self(a) intersect that(a))
      case a if self containsAxis a =>
        a -> self(a)
      case a =>
        a -> that(a)
    }.toMap
  }

  def all: Iterable[Case] = new Iterable[Case] {
    def iterator: Iterator[Case] = {
      val (axes, valueSets) = self.underlying.toArray.reverse.unzip
      val values = valueSets.map(_.toArray) // axes; possible values
      val n = axes.length

      new Iterator[Case] {
        private[this] val indices = Array.fill(n)(0)
        if (n > 0) indices(0) = -1
        private[this] var finished = false

        def hasNext = !finished
        def next(): Case = {
          if (n == 0) {
            finished = true
            return Case(Map())
          }
          var i = 0
          while (i < n) {
            if (indices(i) < values(i).length - 1) {
              indices(i) += 1
              finished = (0 until n).forall(i => indices(i) == values(i).length - 1)
              return Case((axes lazyZip values lazyZip indices).map { case (a, v, i) => a -> v(i) }.toMap)
            }
            else indices(i) = 0
            i += 1
          }
          throw new IllegalStateException()  // bad state, should never be here
        }
      }
    }
  }

  override def toString = {
    underlying.map { case (a, ks) =>
      s"{$a: ${ks.mkString(" ")}}"
    }.mkString(" × ")
  }

}

object CaseCube {

  def apply(underlying: Map[Name, Set[String]]) = new CaseCube(underlying)

}
