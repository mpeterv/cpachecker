/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2013  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.cdt.internal.core.dom.parser.c.CFunctionType;
import org.sosy_lab.common.Pair;
import org.sosy_lab.cpachecker.cfa.ast.c.CArraySubscriptExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFieldReference;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeIdExpression.TypeIdOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeIdInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCCodeException;
import org.sosy_lab.cpachecker.util.predicates.interfaces.Formula;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap.SSAMapBuilder;
import org.sosy_lab.cpachecker.util.predicates.pathformula.Variable;
import org.sosy_lab.cpachecker.util.predicates.pathformula.withUF.PointerTargetSet;
import org.sosy_lab.cpachecker.util.predicates.pathformula.withUF.PointerTargetSet.PointerTargetSetBuilder;

public class ExpressionToFormulaWithUFVisitor extends ExpressionToFormulaVisitor {

  public ExpressionToFormulaWithUFVisitor(final CToFormulaWithUFConverter cToFormulaConverter,
                                          final CFAEdge cfaEdge,
                                          final String function,
                                          final SSAMapBuilder ssa,
                                          final Constraints constraints,
                                          final PointerTargetSetBuilder pts) {

    super(cToFormulaConverter, cfaEdge, function, ssa, constraints);

    this.conv = cToFormulaConverter;
    this.pts = pts;

    this.baseVisitor = new BaseVisitor(conv, cfaEdge, pts);
  }

  @Override
  public Formula visit(final CArraySubscriptExpression e) throws UnrecognizedCCodeException {
    final Formula base = e.getArrayExpression().accept(this);
    final Formula index = e.getSubscriptExpression().accept(this);
    final int size = pts.getSize(e.getExpressionType());
    final Formula coeff = conv.fmgr.makeNumber(pts.getPointerType(), size);
    final Formula offset = conv.fmgr.makeMultiply(coeff, index);
    lastAddress = conv.fmgr.makePlus(base, offset);
    final CType resultType = PointerTargetSet.simplifyType(e.getExpressionType());
    return conv.isCompositeType(resultType) ? lastAddress :
           conv.makeDereferece(resultType, lastAddress, ssa, pts);
  }

  @Override
  public Formula visit(final CFieldReference e) throws UnrecognizedCCodeException {
    assert !e.isPointerDereference() : "CFA should be transformed to eliminate ->s";
    final Variable variable = e.accept(baseVisitor);
    final CType resultType = PointerTargetSet.simplifyType(e.getExpressionType());
    if (variable != null) {
      if (!conv.isCompositeType(resultType)) {
        lastAddress = null;
        return conv.makeVariable(variable, ssa);
      } else {
        throw new UnrecognizedCCodeException("Unhandled reference to composite as a whole", edge, e);
      }
    } else {
      final CType fieldOwnerType = e.getFieldOwner().getExpressionType();
      if (fieldOwnerType instanceof CCompositeType) {
        final Formula base = e.getFieldOwner().accept(this);
        final String fieldName = e.getFieldName();
        fields.add(Pair.of((CCompositeType) fieldOwnerType, fieldName));
        final Formula offset = conv.fmgr.makeNumber(pts.getPointerType(),
                                                    pts.getOffset((CCompositeType) fieldOwnerType, fieldName));
        lastAddress = conv.fmgr.makePlus(base, offset);
        return conv.isCompositeType(resultType) ? lastAddress :
               conv.makeDereferece(resultType, lastAddress, ssa, pts);
      } else {
        throw new UnrecognizedCCodeException("Field owner of a non-composite type", edge, e);
      }
    }
  }

  @Override
  public Formula visit(final CIdExpression e) throws UnrecognizedCCodeException {
    Variable variable = e.accept(baseVisitor);
    final CType resultType = PointerTargetSet.simplifyType(e.getExpressionType());
    if (variable != null) {
      if (!conv.isCompositeType(resultType)) {
        lastAddress = null;
        return conv.makeVariable(variable, ssa);
      } else {
        throw new UnrecognizedCCodeException("Unhandled reference to composite as a whole", edge, e);
      }
    } else {
      variable = conv.scopedIfNecessary(e, ssa, function);
      lastAddress = conv.makeConstant(variable.withType(new CPointerType(true, false, e.getExpressionType())), ssa);
      return conv.isCompositeType(resultType) ? lastAddress :
             conv.makeDereferece(resultType, lastAddress, ssa, pts);
    }
  }

  @Override
  public Formula visit(final CTypeIdExpression e) throws UnrecognizedCCodeException {
    if (e.getOperator() == TypeIdOperator.SIZEOF) {
      return handleSizeof(e, e.getType());
    } else {
      return visitDefault(e);
    }
  }

  private Formula handleSizeof(final CExpression e, final CType type) throws UnrecognizedCCodeException {
    return conv.fmgr.makeNumber(conv.getFormulaTypeFromCType(e.getExpressionType()), pts.getSize(type));
  }

  @Override
  public Formula visit(CTypeIdInitializerExpression e) throws UnrecognizedCCodeException {
    throw new UnrecognizedCCodeException("Unhandled initializer", edge, e);
  }

  @Override
  public Formula visit(final CUnaryExpression e) throws UnrecognizedCCodeException {
    final CExpression operand = e.getOperand();
    final CType resultType = PointerTargetSet.simplifyType(e.getExpressionType());
    switch (e.getOperator()) {
    case MINUS:
    case PLUS:
    case NOT:
    case TILDE:
      return super.visit(e);
    case SIZEOF:
      return handleSizeof(e, e.getExpressionType());
    case STAR:
      if (!(resultType instanceof CFunctionType)) {
        lastAddress = operand.accept(this);
        return conv.isCompositeType(resultType) ? lastAddress :
               conv.makeDereferece(resultType, lastAddress, ssa, pts);
      } else {
        lastAddress = null;
        return operand.accept(this);
      }
    case AMPER:
      if (!(resultType instanceof CFunctionType)) {
        Variable baseVariable = operand.accept(baseVisitor);
        if (baseVariable == null) {
          Formula addressExpression = operand.accept(this);
          if (!conv.isCompositeType(PointerTargetSet.simplifyType(operand.getExpressionType()))) {
            addressExpression = lastAddress;
            lastAddress = null;
            return addressExpression;
          } else {
            return addressExpression;
          }
        } else {
          baseVariable = baseVisitor.getLastBase();
          final Formula baseAddress = conv.makeConstant(baseVariable, ssa);
          conv.addSharingConstraints(edge,
                                     baseAddress,
                                     baseVariable,
                                     fields,
                                     ssa,
                                     constraints,
                                     pts);
          if (ssa.getIndex(baseVariable.getName()) != CToFormulaWithUFConverter.VARIABLE_UNSET) {
            ssa.deleteVariable(baseVariable.getName());
          }
          pts.addBase(baseVariable.getName(), baseVariable.getType());
          return visit(e);
        }
      } else {
        lastAddress = null;
        return operand.accept(this);
      }
      default:
        throw new UnrecognizedCCodeException("Unknown unary operator", edge, e);
    }
  }

  public Formula getLastAddress() {
    return lastAddress;
  }

  @SuppressWarnings("hiding")
  protected final CToFormulaWithUFConverter conv;
  protected final PointerTargetSetBuilder pts;

  protected final BaseVisitor baseVisitor;

  protected Formula lastAddress;
  protected final List<Pair<CCompositeType, String>> fields = new ArrayList<>();
}
