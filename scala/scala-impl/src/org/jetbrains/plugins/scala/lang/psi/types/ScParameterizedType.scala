package org.jetbrains.plugins.scala
package lang
package psi
package types

/**
 * @author ilyas
 */

import java.util.Objects
import java.util.concurrent.ConcurrentMap

import com.intellij.psi._
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScTypeAlias, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypeParametersOwner
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.{ScDesignatorType, ScProjectionType}
import org.jetbrains.plugins.scala.lang.psi.types.api.{ParameterizedType, TypeParameterType, TypeVisitor, UndefinedType, ValueType}
import org.jetbrains.plugins.scala.lang.psi.types.nonvalue.ScTypePolymorphicType
import org.jetbrains.plugins.scala.lang.psi.types.recursiveUpdate.ScSubstitutor
import org.jetbrains.plugins.scala.lang.refactoring.util.ScTypeUtil.AliasType

class ScParameterizedType private(val designator: ScType, val typeArguments: Seq[ScType]) extends ParameterizedType with ScalaType {

  override protected def isAliasTypeInner: Option[AliasType] = {
    designator match {
      case ScDesignatorType(ta: ScTypeAlias) =>
        computeAliasType(ta, ScSubstitutor.empty)
      case ScProjectionType.withActual(ta: ScTypeAlias, subst) =>
        computeAliasType(ta, subst)
      case _ => None
    }
  }

  private def computeAliasType(ta: ScTypeAlias, subst: ScSubstitutor): Some[AliasType] = {
    val genericSubst = ScSubstitutor.bind(ta.typeParameters, typeArguments)

    val s = subst.followed(genericSubst)
    val lowerBound = ta.lowerBound.map(s.subst)
    val upperBound =
      if (ta.isDefinition) lowerBound
      else ta.upperBound.map(s.subst)
    Some(AliasType(ta, lowerBound, upperBound))
  }

  private var hash: Int = -1

  //noinspection HashCodeUsesVar
  override def hashCode: Int = {
    if (hash == -1)
      hash = Objects.hash(designator, typeArguments)

    hash
  }

  protected override def substitutorInner: ScSubstitutor = {
    designator match {
      case tpt: TypeParameterType =>
        ScSubstitutor.bind(tpt.typeParameters, typeArguments)
      case _ => designator.extractDesignatedType(expandAliases = false) match {
        case Some((owner: ScTypeParametersOwner, s)) =>
          s.followed(ScSubstitutor.bind(owner.typeParameters, typeArguments))
        case Some((owner: PsiTypeParameterListOwner, s)) =>
          s.followed(ScSubstitutor.bind(owner.getTypeParameters, typeArguments))
        case _ => ScSubstitutor.empty
      }
    }
  }

  override def equivInner(r: ScType, constraints: ConstraintSystem, falseUndef: Boolean): ConstraintsResult = {
    val Conformance: ScalaConformance = typeSystem
    val Nothing = projectContext.stdTypes.Nothing

    (this, r) match {
      case (ParameterizedType(Nothing, _), Nothing) => constraints
      case (ParameterizedType(Nothing, _), ParameterizedType(Nothing, _)) => constraints
      case (ParameterizedType(ScAbstractType(tp, lower, upper), args), _) =>
        if (falseUndef) return ConstraintsResult.Failure

        val subst = ScSubstitutor.bind(tp.typeParameters, args)
        var conformance = r.conforms(subst.subst(upper), constraints)
        if (conformance.isFailure) return ConstraintsResult.Failure

        conformance = subst.subst(lower).conforms(r, conformance.constraints)
        if (conformance.isFailure) return ConstraintsResult.Failure

        conformance
      case (ParameterizedType(proj@ScProjectionType(_, _), _), _) if proj.actualElement.isInstanceOf[ScTypeAliasDefinition] =>
        isAliasType match {
          case Some(AliasType(_: ScTypeAliasDefinition, lower, _)) =>
            (lower match {
              case Right(tp) => tp
              case _ => return ConstraintsResult.Failure
            }).equiv(r, constraints, falseUndef)
          case _ => ConstraintsResult.Failure
        }
      case (ParameterizedType(ScDesignatorType(_: ScTypeAliasDefinition), _), _) =>
        isAliasType match {
          case Some(AliasType(_: ScTypeAliasDefinition, lower, _)) =>
            (lower match {
              case Right(tp) => tp
              case _ => return ConstraintsResult.Failure
            }).equiv(r, constraints, falseUndef)
          case _ => ConstraintsResult.Failure
        }
      case (ParameterizedType(UndefinedType(_, _), _), ParameterizedType(_, _)) =>
        Conformance.processHigherKindedTypeParams(this, r.asInstanceOf[ParameterizedType], constraints, falseUndef)
      case (ParameterizedType(_, _), ParameterizedType(UndefinedType(_, _), _)) =>
        Conformance.processHigherKindedTypeParams(r.asInstanceOf[ParameterizedType], this, constraints, falseUndef)
      case (ParameterizedType(_, _), ParameterizedType(designator1, typeArgs1)) =>
        var lastConstraints = constraints
        var t = designator.equiv(designator1, constraints, falseUndef)
        if (t.isFailure) return ConstraintsResult.Failure
        lastConstraints = t.constraints
        if (typeArguments.length != typeArgs1.length) return ConstraintsResult.Failure
        val iterator1 = typeArguments.iterator
        val iterator2 = typeArgs1.iterator
        while (iterator1.hasNext && iterator2.hasNext) {
          t = iterator1.next().equiv(iterator2.next(), lastConstraints, falseUndef)

          if (t.isFailure) return ConstraintsResult.Failure

          lastConstraints = t.constraints
        }
        lastConstraints
      case _ => ConstraintsResult.Failure
    }
  }


  override def visitType(visitor: TypeVisitor): Unit = visitor match {
    case scalaVisitor: ScalaTypeVisitor => scalaVisitor.visitParameterizedType(this)
    case _ =>
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[ScParameterizedType]

  override def equals(other: Any): Boolean = other match {
    case that: ScParameterizedType =>
      (that canEqual this) &&
        designator == that.designator &&
        typeArguments == that.typeArguments
    case _ => false
  }
}

object ScParameterizedType {

  val cache: ConcurrentMap[(ScType, Seq[ScType]), ValueType] =
    ContainerUtil.newConcurrentMap[(ScType, Seq[ScType]), ValueType]()

  def apply(designator: ScType, typeArgs: Seq[ScType]): ValueType = {
    def createCompoundProjectionParameterized(pt: ScParameterizedType): ValueType = {
      pt.isAliasType match {
        case Some(AliasType(_: ScTypeAliasDefinition, _, Right(upper: ValueType))) => upper
        case _ => pt
      }
    }

    val simple = new ScParameterizedType(designator, typeArgs)
    designator match {
      case ScProjectionType(_: ScCompoundType, _) =>
        cache.atomicGetOrElseUpdate((designator, typeArgs),
          createCompoundProjectionParameterized(simple))
      case ScTypePolymorphicType(internalType, typeParameters) if internalType.isInstanceOf[ScParameterizedType] =>
        val internal = internalType.asInstanceOf[ScParameterizedType]
        new ScParameterizedType(internal.designator, internal.typeArguments.map {
          case pType: TypeParameterType =>
            typeParameters.zip(typeArgs).find{case (tParam, _) => TypeParameterType(tParam).equiv(pType)}.map(_._2).getOrElse(pType)
          case aType =>
            aType
        })
      case _ => simple
    }
  }
}
