package pisa.utils

/*
This is adapted from contents in Dominique Unruh's package scala-isabelle
The package can be found on github at https://github.com/dominique-unruh/scala-isabelle
This particular file is adapted from https://github.com/dominique-unruh/scala-isabelle/blob/master/src/test/scala/de/unruh/isabelle/experiments/TheoryManager.scala
 */

import java.nio.file.{Path, Paths}
import scala.concurrent.ExecutionContext
import de.unruh.isabelle.control.{Isabelle, OperationCollection}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0}
import de.unruh.isabelle.pure.{Position, Theory, TheoryHeader, ToplevelState}
import de.unruh.isabelle.mlvalue.{AdHocConverter, MLFunction, MLFunction0, MLFunction2, MLFunction3}
import pisa.utils.NewManager.{Heap, Source, Text}
import TheoryManager.Ops

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

object Transition extends AdHocConverter("Toplevel.transition")

class NewManager {
  def getTheorySource(name: String): Source = Heap(name)
  def getTheory(source: Source)(implicit isabelle: Isabelle): Theory = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    val init_toplevel: MLFunction0[ToplevelState] = compileFunction0[ToplevelState]("Toplevel.init_toplevel")
    val parse_text: MLFunction2[Theory, String, List[(Transition.T, String)]] =
    compileFunction[Theory, String, List[(Transition.T, String)]](
    """fn (thy, text) => let
      |  val transitions = Outer_Syntax.parse_text thy (K thy) Position.start text
      |  fun addtext symbols [tr] =
      |        [(tr, implode symbols)]
      |    | addtext _ [] = []
      |    | addtext symbols (tr::nextTr::trs) = let
      |        val (this,rest) = Library.chop (Position.distance_of (Toplevel.pos_of tr, Toplevel.pos_of nextTr) |> Option.valOf) symbols
      |        in (tr, implode this) :: addtext rest (nextTr::trs) end
      |  in addtext (Symbol.explode text) transitions end""".stripMargin)
    val command_exception: MLFunction3[Boolean, Transition.T, ToplevelState, ToplevelState] =
      compileFunction[Boolean, Transition.T, ToplevelState, ToplevelState](
        "fn (int, tr, st) => Toplevel.command_exception int tr st")
    val toplevel_end_theory: MLFunction[ToplevelState, Theory] = compileFunction[ToplevelState, Theory]("Toplevel.end_theory Position.none")

    source match {
    case Heap(name) => Theory(name)
    case Text(text, path, position) =>
      var toplevel = init_toplevel().force.retrieveNow
      var thy0 = beginTheory(source)
      for ((transition, text) <- parse_text(thy0, text).force.retrieveNow) {
        toplevel = command_exception(true, transition, toplevel).retrieveNow.force
      }
      toplevel_end_theory(toplevel).retrieveNow.force
  }
}
    
  def beginTheory(source: Source)(implicit isabelle: Isabelle): Theory = {
    val header = getHeader(source)
    val masterDir = source.path.getParent
    implicit val ec: ExecutionContext = ExecutionContext.global
    Ops.begin_theory(masterDir, header, header.imports.map(getTheorySource).map(getTheory)).retrieveNow
  }
  def getHeader(source: Source)(implicit isabelle: Isabelle): TheoryHeader = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    source match {
      case Text(text, path, position) => Ops.header_read(text, position).retrieveNow
    }
  }
}

object NewManager extends OperationCollection {
  implicit val ec: ExecutionContext = ExecutionContext.global
  trait Source { def path : Path }
  case class Heap(name: String) extends Source {
    override def path: Path = Paths.get("INVALID")
  }
  case class File(path: Path) extends Source
  case class Text(text: String, path: Path, position: Position) extends Source
  object Text {
    def apply(text: String, path: Path)(implicit isabelle: Isabelle): Text = new Text(text, path, Position.none)
  }

  //noinspection TypeAnnotation
  protected final class Ops(implicit isabelle: Isabelle) {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val header_read = compileFunction[String, Position, TheoryHeader]("fn (text,pos) => Thy_Header.read pos text")
    val begin_theory = compileFunction[Path, TheoryHeader, List[Theory], Theory](
      "fn (path, header, parents) => Resources.begin_theory path header parents")
  }

  override protected def newOps(implicit isabelle: Isabelle, ec: ExecutionContext) = {
    new this.Ops
  }
}
