/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cluster;

import com.hazelcast.core.*;
import com.hazelcast.impl.NamedExecutorService;
import com.hazelcast.util.Clock;
import com.hazelcast.impl.MemberImpl;
import com.hazelcast.impl.Node;
import com.hazelcast.nio.Address;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

public class ClusterImpl implements Cluster {

    final CopyOnWriteArraySet<MembershipListener> listeners = new CopyOnWriteArraySet<MembershipListener>();
    final AtomicReference<Set<MemberImpl>> members = new AtomicReference<Set<MemberImpl>>();
    final AtomicReference<MemberImpl> localMember = new AtomicReference<MemberImpl>();
    final Map<Address, MemberImpl> memberAddressMap = new ConcurrentHashMap<Address, MemberImpl>();
    final Map<MemberImpl, MemberImpl> memberMap = new ConcurrentHashMap<MemberImpl, MemberImpl>();

    @SuppressWarnings("VolatileLongOrDoubleField")
    volatile long clusterTimeDiff = Long.MAX_VALUE;
    final Node node;
    final Object memberChangeMutex = new Object();

    public ClusterImpl(Node node) {
        this.node = node;
        reset();
    }

    public void reset() {
        setMembers(Arrays.asList(node.getLocalMember()));
    }

    public void setMembers(List<MemberImpl> lsMembers) {
        final Set<MemberImpl> newMembers = new LinkedHashSet<MemberImpl>(lsMembers.size());
        final Set<MemberImpl> oldMembers = members.get();

        final List<MemberImpl> addedMembers = new LinkedList<MemberImpl>();
        final List<MemberImpl> removedMembers = new LinkedList<MemberImpl>();

        //checking for added members
        for (MemberImpl incomingMember : lsMembers) {
            //todo: what is the added value of copying to dummy, why is the incomingMember not used directly?
            MemberImpl dummy = new MemberImpl(incomingMember.getAddress(), incomingMember.localMember(), incomingMember.getNodeType(), incomingMember.getUuid());
            MemberImpl member = memberMap.get(dummy);
            if (member == null) {
                //the member previously didn't exist, so its an added member.

                member = dummy;
                addedMembers.add(member);
                memberMap.put(member, member);
                memberAddressMap.put(member.getAddress(), member);
            }

            if (member.localMember()) {
                localMember.set(member);
            }
            newMembers.add(member);
        }

        //checking for removed members
        for (MemberImpl oldMember : oldMembers) {
            if (!newMembers.contains(oldMember)) {
                //so the old member doesn't exist anymore, so it needs to be removed.
                removedMembers.add(oldMember);
                memberMap.remove(oldMember);
                memberAddressMap.remove(oldMember.getAddress());
            }
        }

        //we need to have this lock here before the listeners collection is going to be used. If we fail to do so,
        //it can lead to InitialMembershipListeners that are registered, have not yet received their InitialMembershipEvent
        //but will normal MembershipEvent. This would break the contract.
        //In practice it is very unlikely that this lock is going to be contented and the 'setMembers' method is not going
        //to be called very frequently. So from a performance point of view it isn't a concern. When biased locking is enabled
        //(default in Oracle JDK 6) the costs will be reduced even more.
        //check if any members have been added
        synchronized (memberChangeMutex){
            members.set(Collections.unmodifiableSet(newMembers));

            //if there are no listeners, we are done.
            if(listeners.isEmpty()){
                return;
            }

            LinkedHashSet<Member> membersAfterEvent = new LinkedHashSet<Member>(oldMembers);
            final NamedExecutorService eventExecutor = node.executorManager.getEventExecutorService();
            for (Member addedMember : addedMembers) {
                membersAfterEvent.add(addedMember);

                final MembershipEvent event = new MembershipEvent(this, addedMember, MembershipEvent.MEMBER_ADDED,
                        Collections.unmodifiableSet(new LinkedHashSet<Member>(membersAfterEvent)));
                for (final MembershipListener listener : listeners) {
                    eventExecutor.executeOrderedRunnable(listener.hashCode(), new Runnable() {
                        public void run() {
                            listener.memberAdded(event);
                        }
                    });
                }
            }

            for (Member removedMember : removedMembers) {
                membersAfterEvent.remove(removedMember);

                final MembershipEvent event = new MembershipEvent(this, removedMember, MembershipEvent.MEMBER_REMOVED,
                        Collections.unmodifiableSet(new LinkedHashSet<Member>(membersAfterEvent)));
                for (final MembershipListener listener : listeners) {
                    eventExecutor.executeOrderedRunnable(listener.hashCode(), new Runnable() {
                        public void run() {
                            listener.memberRemoved(event);
                        }
                    });
                }
            }
        }
    }

    public void addMembershipListener(MembershipListener listener) {
        if(!(listener instanceof InitialMembershipListener)){
            listeners.add(listener);
        }else{
            synchronized (memberChangeMutex) {
                if(!listeners.add(listener)){
                    //the listener is already registered, so we are not going to send the initialize event.
                    return;
                }

                final InitialMembershipListener initializingListener = (InitialMembershipListener) listener;
                final InitialMembershipEvent event = new InitialMembershipEvent(this, getMembers());

                node.executorManager.getEventExecutorService().executeOrderedRunnable(listener.hashCode(), new Runnable(){
                    public void run() {
                        initializingListener.init(event);
                    }
                });
            }
        }
    }

    public void removeMembershipListener(MembershipListener listener) {
        listeners.remove(listener);
    }

    public Member getLocalMember() {
        return localMember.get();
    }

    public Set<Member> getMembers() {
        //ieeeuwwwwww
        return (Set)members.get();
    }

    public long getClusterTime() {
        return Clock.currentTimeMillis() + ((clusterTimeDiff == Long.MAX_VALUE) ? 0 : clusterTimeDiff);
    }

    public void setMasterTime(long masterTime) {
        long diff = masterTime - Clock.currentTimeMillis();
        if (Math.abs(diff) < Math.abs(clusterTimeDiff)) {
            this.clusterTimeDiff = diff;
        }
    }

    public long getClusterTimeFor(long localTime) {
        return localTime + ((clusterTimeDiff == Long.MAX_VALUE) ? 0 : clusterTimeDiff);
    }

    public Member getMember(Address address) {
        return memberAddressMap.get(address);
    }

    @Override
    public String toString() {
        Set<Member> members = getMembers();
        StringBuffer sb = new StringBuffer("Cluster [");
        if (members != null) {
            sb.append(members.size());
            sb.append("] {");
            for (Member member : members) {
                sb.append("\n\t").append(member);
            }
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static class Notification implements Runnable{
        private final MembershipListener listener;
        private final MembershipEvent event;

        private Notification(MembershipEvent event, MembershipListener listener) {
            this.event = event;
            this.listener = listener;
        }

        public void run() {
            switch (event.getEventType()) {
                case MembershipEvent.MEMBER_ADDED:
                    listener.memberAdded(event);
                    break;
                case MembershipEvent.MEMBER_REMOVED:
                    listener.memberRemoved(event);
                    break;
                default:
                    throw new RuntimeException("Unhandeled event: " + event);
            }
        }
    }
}
