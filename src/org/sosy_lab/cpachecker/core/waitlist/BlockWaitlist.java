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
package org.sosy_lab.cpachecker.core.waitlist;

import com.google.common.base.Preconditions;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.callstack.CallstackState;
import org.sosy_lab.cpachecker.util.AbstractStates;


public class BlockWaitlist implements Waitlist {

  private static CallstackState retreiveCallstack(AbstractState pState) {
    return AbstractStates.extractStateByType(pState, CallstackState.class);
  }

  private static class Block {
    public static final String ENTRY_BLOCK_NAME = "entry_block_main";
    //function name which is the basis for the block
    private String name;
    //current number of used resources
    private int countResources;
    //saved number of resources when limit is reached
    private int savedResources;
    //limit for resources
    private int limitResources;
    //main waitlist
    private Waitlist mainWaitlist;
    //is it a block for entry function
    private boolean isEntryBlock;
    //previous block in the list

    Block(BKey key, WaitlistFactory factory, int limit) {
      mainWaitlist = factory.createWaitlistInstance();
      limitResources = limit;
      name = key.name;
    }

    @SuppressWarnings("unused")
    public int getSavedResources() {
      return savedResources;
    }

    /**
     * Add state to main waitlist,
     * increment used resources
     */
    void addStateToMain(AbstractState e) {
      mainWaitlist.add(e);
      incResources(e);
      //System.out.println("BlockWaitlist. (add to main) Resources[" + name + "]=" + countResources);
    }

    /**
     * check resource limits
     * @return true if resource limit has been reached
     */
    boolean checkResources() {
      if(isEntryBlock) {
        //ignore
        return false;
      } else {
        return countResources > limitResources;
      }
    }

    @SuppressWarnings("unused")
    private void incResources(AbstractState e) {
      countResources++;
    }

    @SuppressWarnings("unused")
    private void decResources(AbstractState e) {
      countResources--;
    }

    boolean isEmpty() {
      return mainWaitlist.isEmpty();
    }

    boolean removeState(AbstractState e) {
      boolean b = mainWaitlist.remove(e);

      //System.out.println("block " + name + ", isEmpty=" + isEmpty());

      if(b) {
        //remove resources for e in block
        decResources(e);
      }
      return b;
    }

    AbstractState popState() {
      AbstractState res;
      if(!mainWaitlist.isEmpty()) {
        res = mainWaitlist.pop();
        //remove resources for e
        //decResources(res);
        return res;
      } else {
        assert false : "invalid pop: current block is empty";
        return null;
      }
    }
  }

  private static class BKey implements Comparable<BKey> {
    String name;
    int callStackDepth;

    BKey(String pName, int pDepth) {
      name = pName;
      callStackDepth = pDepth;
    }

    @Override
    public int compareTo(BKey k2) {
      if(callStackDepth!=k2.callStackDepth) {
        return Integer.compare(callStackDepth, k2.callStackDepth);
      }
      return name.compareTo(k2.name);
    }

    @Override
    public String toString() {
      return "[" + name + ", " + callStackDepth + "]";
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + callStackDepth;
      result = prime * result + ((name == null) ? 0 : name.hashCode());
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
      BKey other = (BKey) obj;
      if (callStackDepth != other.callStackDepth) {
        return false;
      }
      if (name == null) {
        if (other.name != null) {
          return false;
        }
      } else if (!name.equals(other.name)) {
        return false;
      }
      return true;
    }
  }

  private final WaitlistFactory wrappedWaitlist;

  private int size = 0;
  //the map of active blocks (for efficient state removal)
  private final NavigableMap<BKey, Block> activeBlocksMap = new TreeMap<>();
  //map of inactive blocks (where resource limits are reached)
  private final NavigableMap<BKey,Block> inactiveBlocksMap = new TreeMap<>();
  //resource limit
  private int resourceLimit;
  /**
   * Constructor that needs a factory for the waitlist implementation that
   * should be used to store states with the same block.
   */
  protected BlockWaitlist(WaitlistFactory pSecondaryStrategy, int limit) {
    wrappedWaitlist = Preconditions.checkNotNull(pSecondaryStrategy);
    resourceLimit = limit;
  }

  /**
   * add new block as the last element in the activeList
   *
   * @param key - key of the block
   * @param pState - first state to be added
   */
  private void addNewBlock(BKey key, AbstractState pState) {
    Block b;
    if(activeBlocksMap.containsKey(key)) {
      b = activeBlocksMap.get(key);
    } else {
      b = new Block(key, wrappedWaitlist, resourceLimit);
      activeBlocksMap.put(key, b);
      b.isEntryBlock = key.name.equals(Block.ENTRY_BLOCK_NAME);
    }
    size++;
    b.addStateToMain(pState);
  }

  /**
   * mark last active block as inactive
   */
  private void makeBlockInactive(BKey key) {
    System.out.println("BlockWaitlist. Make block inactive " + key);
    Block b = activeBlocksMap.get(key);
    inactiveBlocksMap.put(key, b);
    //save resource count
    b.savedResources = b.countResources;
    //TODO: remove from active blocks?
    activeBlocksMap.remove(key);
  }

  Pattern ldvPattern = Pattern.compile("ldv_.*_instance_.*");
  /**
   * checks whether function name is a block
   * (for example, starts with emg_control or emg_callback
   * or matches ldv_.*_instance_)
   * @return true if it is a block entry
   */
  private boolean isBlock(String func) {
    Matcher matcher = ldvPattern.matcher(func);
    boolean b = matcher.matches();
    //System.out.println("func " + func + "=" + b);
    return b;
  }

  /**
   * @return key for the block
   */
  private BKey getBlockKey(AbstractState e) {
    CallstackState callStack = retreiveCallstack(e);
    String resFunc = Block.ENTRY_BLOCK_NAME;
    int resDepth = 1;
    while(callStack!=null) {
      //get current function
      String func = callStack.getCurrentFunction();
      if(isBlock(func)) {
        resFunc = func;
        resDepth = callStack.getDepth();
        break;
      }
      callStack = callStack.getPreviousState();
    }
    return new BKey(resFunc,resDepth);
  }

  /**
   * get block for state e
   * @param e the state for which we need a block
   * @return block for state e
   */
  private Block getBlockForState(AbstractState e) {
    BKey key = getBlockKey(e);
    assert key!=null;

    //search block in active blocks
    Block block = activeBlocksMap.get(key);
    if(block != null) {
      return block;
    }

    //search block in inactive blocks
    block = inactiveBlocksMap.get(key);
    if(block != null) {
      return block;
    }
    return null;
  }

  @Override
  public Iterator<AbstractState> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(AbstractState pState) {
    BKey key = getBlockKey(pState);
    size++;

    //for debug only
    CallstackState callStack = retreiveCallstack(pState);
    String func = callStack.getCurrentFunction();
    //System.out.println("BlockWaitlist. Add state=" + pState + ", size=" + size);

    if(inactiveBlocksMap.containsKey(key)) {
      //System.out.println("BlockWaitlist. inactive block " + key + " for " + func);
      //TODO: optimization - do not add
      Block block = inactiveBlocksMap.get(key);
      block.addStateToMain(pState);
    } else {
      Block b = activeBlocksMap.get(key);
      if(b!=null) {
        //System.out.println("BlockWaitlist. existing block " + key + " for " + func);
        b.addStateToMain(pState);
        if(b.checkResources()) {
          //stop analysis for the current block
          makeBlockInactive(key);
        }
      } else {
        //System.out.println("BlockWaitlist. new block " + key + " for " + func);
        addNewBlock(key, pState);
      }
    }
  }

  @Override
  public boolean contains(AbstractState pState) {
    Block block = getBlockForState(pState);
    if(block == null) {
        return false;
    }
    return block.mainWaitlist.contains(pState);
  }

  @Override
  public boolean remove(AbstractState pState) {
    //System.out.println("BlockWaitlist. Remove state=" + pState);
    //remove may be called even if the state is not in the waitlist
    Block block = getBlockForState(pState);
    if(block==null) {
      return false;
    }
    //System.out.println("Found block " + block.name);
    boolean b = block.removeState(pState);
    //System.out.println("removeState=" + b);
    if(!b) {
      return false;
    }
    /*
    Entry<BKey, Block> e = activeBlocksMap.lastEntry();
    while(e!=null && e.getValue().isEmpty()) {
      System.out.println("BlockWaitlist. Remove empty block=" + e.getKey() + ", resources=" + e.getValue().countResources);
      activeBlocksMap.pollLastEntry();
      e = activeBlocksMap.lastEntry();
    }*/
    size--;
    //System.out.println("size=" + size);
    return true;
  }

  boolean unknownIfHasInactive = true;
  @Override
  public AbstractState pop() {
    assert !isEmpty();
    Entry<BKey, Block> e = activeBlocksMap.lastEntry();
    while(e!=null && e.getValue().isEmpty()) {
      System.out.println("BlockWaitlist. Pop.Remove empty block=" + e.getKey() + ", resources=" + e.getValue().countResources);
      activeBlocksMap.pollLastEntry();
      e = activeBlocksMap.lastEntry();
    }

    if(unknownIfHasInactive && isEmptyMap()) {
      throw new RuntimeException("Waitlist contains only inactive blocks " + inactiveBlocksMap.keySet());
    }
    assert !isEmpty();
    Entry<BKey, Block> highestEntry = activeBlocksMap.lastEntry();
    AbstractState state = highestEntry.getValue().popState();
    //System.out.println("BlockWaitlist. Pop state=" + state);
    size--;

    return state;
  }

  @Override
  public int size() {
    return size;
  }

  private boolean isEmptyMap() {
    if(activeBlocksMap.isEmpty()) {
      return true;
    }
    //fast detection if last block is not empty
    Entry<BKey, Block> highestEntry = activeBlocksMap.lastEntry();
    if(!highestEntry.getValue().isEmpty()) {
      return false;
    }

    //slow path
    System.out.println("isEmptyMap. Slow path");
    Iterator<BKey> i = activeBlocksMap.descendingKeySet().iterator();

    while(i.hasNext()) {
      BKey key = i.next();
      Block b = activeBlocksMap.get(key);
      if(!b.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean isEmpty() {
    if(unknownIfHasInactive) {
      return size==0;
    }
    return isEmptyMap();
  }

  @Override
  public void clear() {
    activeBlocksMap.clear();
    inactiveBlocksMap.clear();
    size = 0;
  }

  public static WaitlistFactory factory(final WaitlistFactory pSecondaryStrategy, int resourceLimit) {
    return () -> new BlockWaitlist(pSecondaryStrategy, resourceLimit);
  }
}
