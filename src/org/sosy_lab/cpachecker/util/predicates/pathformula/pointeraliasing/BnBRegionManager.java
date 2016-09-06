/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
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
package org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Multimap;

import org.sosy_lab.cpachecker.cfa.types.c.CType;


public class BnBRegionManager implements MemoryRegionManager {
  private static final String GLOBAL = "global";
  private static final String SEPARATOR = "_";


  protected static class GlobalBnBRegion implements MemoryRegion {
    @Override
    public String toString() {
      return "GlobalBnBRegion [type=" + type + "]";
    }

    private final CType type;

    protected GlobalBnBRegion(CType pType) {
      this.type = pType;
    }

    @Override
    public CType getType() {
      return type;
    }

    @Override
    public String getName() {
      return
          CToFormulaConverterWithPointerAliasing.getPointerAccessNameForType(type)
          + SEPARATOR
          + GLOBAL;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((type == null) ? 0 : type.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      GlobalBnBRegion other = (GlobalBnBRegion) obj;
      if (type == null) {
        if (other.type != null) {
          return false;
        }
      } else if (!type.equals(other.type)) {
        return false;
      }
      return true;
    }

  }

  protected static class FieldBnBRegion implements MemoryRegion {

    private final CType fieldOwnerType;
    private final String fieldName;

    protected FieldBnBRegion(CType pFieldOwnerType, String pFieldName) {
      fieldOwnerType = pFieldOwnerType;
      fieldName = pFieldName;
    }

    @Override
    public CType getType() {
      return fieldOwnerType;
    }

    @Override
    public String getName() {
      return CToFormulaConverterWithPointerAliasing.getPointerAccessNameForType(fieldOwnerType)
      + SEPARATOR
      + fieldName;
    }

    @Override
    public String toString() {
      return "FieldBnBRegion [fieldOwnerType=" + fieldOwnerType
          + ", fieldName=" + fieldName + "]";
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((fieldName == null) ? 0 : fieldName.hashCode());
      result = prime * result + ((fieldOwnerType == null) ? 0 : fieldOwnerType.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      FieldBnBRegion other = (FieldBnBRegion) obj;
      if (fieldName == null) {
        if (other.fieldName != null) {
          return false;
        }
      } else if (!fieldName.equals(other.fieldName)) {
        return false;
      }
      if (fieldOwnerType == null) {
        if (other.fieldOwnerType != null) {
          return false;
        }
      } else if (!fieldOwnerType.equals(other.fieldOwnerType)) {
        return false;
      }
      return true;
    }
  }

  private final Multimap<CType, String> fieldRegions;

  public BnBRegionManager(Multimap<CType, String> fieldRegions) {
    this.fieldRegions = fieldRegions;
  }

  @Override
  public String getPointerAccessName(MemoryRegion pRegion) {
    checkNotNull(pRegion);
    return pRegion.getName();
  }

  @Override
  public MemoryRegion makeMemoryRegion(CType pType) {
    checkNotNull(pType);
    CTypeUtils.checkIsSimplified(pType);
    return new GlobalBnBRegion(pType);
  }

  @Override
  public MemoryRegion makeMemoryRegion(CType pFieldOwnerType, CType pFieldType,
      String pFieldName) {
    checkNotNull(pFieldOwnerType);
    checkNotNull(pFieldType);
    checkNotNull(pFieldName);
    CTypeUtils.checkIsSimplified(pFieldOwnerType);
    CTypeUtils.checkIsSimplified(pFieldType);
    if(fieldRegions.containsEntry(pFieldOwnerType, pFieldName)) {
      //common case - likely
      return new FieldBnBRegion(pFieldOwnerType, pFieldName);
    } else {
      //field inside global region - unlikely
      return new GlobalBnBRegion(pFieldType);
    }
  }

}
