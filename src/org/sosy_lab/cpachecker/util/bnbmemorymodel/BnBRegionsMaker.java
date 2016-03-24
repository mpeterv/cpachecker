/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
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
package org.sosy_lab.cpachecker.util.bnbmemorymodel;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sosy_lab.common.collect.PersistentLinkedList;
import org.sosy_lab.common.collect.PersistentList;
import org.sosy_lab.common.collect.PersistentSortedMap;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType.CCompositeTypeMemberDeclaration;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.PointerTarget;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.PointerTargetSetBuilder;


public class BnBRegionsMaker {

  private List<BnBRegionImpl> regions = new ArrayList<>();
  private Set<CType> containers = new HashSet<>();

  /**
   * Determines whether or not the field is in global region
   * @param parent - element's base
   * @param pMemberType
   * @param name - name of element
   * @return true if global, else otherwise
   */
  public boolean isInGlobalRegion(final CType parent, CType pMemberType, final String name){

    if (regions.isEmpty()){
      return true;
    }

    BnBRegion toCheck = new BnBRegionImpl(pMemberType, parent, name);
    if (regions.contains(toCheck)){
      return false;
    }

    // (new Exception("Not found " + parent + " " + name)).printStackTrace();
    return true;
  }

  /**
   * Gathers information about struct field usage and constructs regions
   * @param cfa - program CFA
   */
  public void makeRegions(final CFA cfa) {
    ComplexTypeFieldStatistics ctfs = new ComplexTypeFieldStatistics();
    ctfs.findFieldsInCFA(cfa);
    //ctfs.dumpStat("Stat.txt");

    Map<CType, HashMap<CType, HashSet<String>>> usedFields = ctfs.getUsedFields();
    Map<CType, HashMap<CType, HashSet<String>>> refdFields = ctfs.getRefdFields();
    Map<CType, HashSet<String>> sub;

    // remove all those fields present in both maps
    for (CType basicType : usedFields.keySet()){
      if (refdFields.containsKey(basicType)){
        sub = usedFields.get(basicType);
        for (CType structType : sub.keySet()){
          Set<String> set = refdFields.get(basicType).get(structType);

          if (set != null){
            usedFields.get(basicType).get(structType).removeAll(set);
          }

        }
      }
    }

    // fill regions
    for (CType basicType : usedFields.keySet()){
      for (CType structType : usedFields.get(basicType).keySet()){

        Set<String> set = usedFields.get(basicType).get(structType);
        if (!set.isEmpty()) {
          for (String name : set){
            regions.add(new BnBRegionImpl(basicType, structType, name));
          }
        }
      }
    }

    for (BnBRegionImpl region : regions){
      containers.add(region.getRegionParent());
    }

  }

  /**
   * Writes information about regions in the specified file
   * @param filename - desired filename
   */
  public void dumpRegions(final String filename){
    File dump = new File(filename);

    try{
      FileWriter writer = new FileWriter(dump);

      String result = "";

      if (!containers.isEmpty()){
        for (CType container : containers){
          result += ((CCompositeType)container).getMembers();
          result += '\n';
        }
      } else {
        result += "Empty containers\n";
      }

      if (!regions.isEmpty()) {
        int i = 0;
        for (BnBRegionImpl reg : regions) {
          result += "Number: " + (i++) + '\n';
          result += "Type: " + reg.getType().toString() + '\n';
          result += "Parent: ";

          if (reg.getRegionParent() == null) {
            result += "NULL";
          } else {
            result += reg.getRegionParent().toString();
          }
          result += '\n';
          result += "Member:\n";
          result += '\t' + reg.getElem() + "\n\n";

        }
      } else {
        result += "Empty regions\n";
      }

      writer.write(result);
      writer.close();

    } catch (IOException e){
      System.out.println(e.getMessage());
    }
  }

  /**
   * Updates targets of the PointerTargetSet taking into account region information
   * @param targets - list of targets in the PointerTargetSet
   * @param ptsb - PointerTargetSetBuilder connected to the holder of @param targets
   * @return targets with the information about regions
   */
  public Map<String, PersistentList<PointerTarget>> getNewTargetsWithRegions(
      final PersistentSortedMap<String, PersistentList<PointerTarget>> targets,
      final PointerTargetSetBuilder ptsb){

    String regName = "";
    Map<String, ArrayList<PointerTarget>> targetRegions = new HashMap<>();
    boolean found = false;

    for (String target : targets.keySet()){
      PersistentList<PointerTarget> pointerTargets = targets.get(target);
      if (!(target.contains(" global") || target.contains(" struct"))){
        for (PointerTarget pt : pointerTargets){
          CType containerType = pt.getContainerType();
          if (containerType instanceof CCompositeType && containers.contains(containerType)){
            int offset = pt.getContainerOffset();
            int curOffset = 0;

            for (CCompositeTypeMemberDeclaration field : ((CCompositeType) containerType).getMembers()){
              if (curOffset == offset){
                if (!isInGlobalRegion(containerType, field.getType(), field.getName())){
                  regName = field.getType().toString() + " " + containerType.toString() + " " + field.getName();
                } else {
                  regName = field.getType().toString() + " global";
                }
                found = true;
                break;
              } else {
                offset += ptsb.getSize(field.getType());
              }
            }
          }
          if (!found) {
            if (!target.contains(" global")) {
              regName = target + " global";
            } else {
              regName = target;
            }
          }
          if (!targetRegions.containsKey(regName)) {
            targetRegions.put(regName, new ArrayList<PointerTarget>());
          }
          targetRegions.get(regName).add(pt);
        }
      } else {
        if (!targetRegions.containsKey(target)){
          targetRegions.put(target, new ArrayList<PointerTarget>());
        }
        for (PointerTarget pt : pointerTargets){
          if (!targetRegions.get(target).contains(pt)){
            targetRegions.get(target).add(pt);
          }
        }
      }
    }

    Map<String, PersistentList<PointerTarget>> newTargets = new HashMap<>();
    for (String type : targetRegions.keySet()){
      newTargets.put(type, PersistentLinkedList.copyOf(targetRegions.get(type)));
    }

    return newTargets;
  }

  /**
   * Constructs new UF name with consideration of the region
   * @param ufName - UF name to use with this CType
   * @param region - null if global or parent_name + " " + field_name
   * @return new UF name for the CType with region information
   */
  public String getNewUfName(final String ufName, String region){
    String result = ufName + "_";
    if (region != null){
      result += region.replace(' ', '_');
    } else {
      result += "global";
      //(new Exception("Global region")).printStackTrace();
    }
    return result;
  }

}
