/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.impl.reactor;

import java.io.InterruptedIOException;

import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactor;
import org.apache.http.nio.reactor.IOReactorException;

public abstract class AbstractMultiworkerIOReactor implements IOReactor {

    private final int workerCount;
    private final BaseIOReactor[] ioReactors;
    private final WorkerThread[] threads;
    
    private int currentWorker = 0;
    
    public AbstractMultiworkerIOReactor(long selectTimeout, int workerCount) 
            throws IOReactorException {
        super();
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count may not be negative or zero");
        }
        this.workerCount = workerCount;
        this.ioReactors = new BaseIOReactor[workerCount];
        this.threads = new WorkerThread[workerCount];
        for (int i = 0; i < this.ioReactors.length; i++) {
            this.ioReactors[i] = new BaseIOReactor(selectTimeout);
        }
    }

    protected void startWorkers(final IOEventDispatch eventDispatch) {
        for (int i = 0; i < this.workerCount; i++) {
            BaseIOReactor ioReactor = this.ioReactors[i];
            this.threads[i] = new WorkerThread(ioReactor, eventDispatch);
        }
        for (int i = 0; i < this.workerCount; i++) {
            this.threads[i].start();
        }
    }

    protected void stopWorkers(int millis) 
            throws InterruptedIOException, IOReactorException {
        for (int i = 0; i < this.workerCount; i++) {
            this.ioReactors[i].shutdown();
        }
        for (int i = 0; i < this.workerCount; i++) {
            try {
                this.threads[i].join(millis);
            } catch (InterruptedException ex) {
                throw new InterruptedIOException(ex.getMessage());
            }
        }
    }
    
    protected void verifyWorkers() 
            throws InterruptedIOException, IOReactorException {
        for (int i = 0; i < this.workerCount; i++) {
            WorkerThread worker = this.threads[i];
            if (!worker.isAlive()) {
                if (worker.getReactorException() != null) {
                    throw worker.getReactorException();
                }
                if (worker.getInterruptedException() != null) {
                    throw worker.getInterruptedException();
                }
            }
        }
    }
    
    protected void addChannel(final ChannelEntry entry) {
        // Distribute new channels among the workers
        this.ioReactors[this.currentWorker++ % this.workerCount].addChannel(entry);
    }
        
    static class WorkerThread extends Thread {

        final BaseIOReactor ioReactor;
        final IOEventDispatch eventDispatch;
        
        private volatile IOReactorException reactorException;
        private volatile InterruptedIOException interruptedException;
        
        public WorkerThread(final BaseIOReactor ioReactor, final IOEventDispatch eventDispatch) {
            super();
            this.ioReactor = ioReactor;
            this.eventDispatch = eventDispatch;
        }
        
        public void run() {
            try {
                this.ioReactor.execute(this.eventDispatch);
            } catch (InterruptedIOException ex) {
                this.interruptedException = ex;
            } catch (IOReactorException ex) {
                this.reactorException = ex;
            } finally {
                try {
                    this.ioReactor.shutdown();
                } catch (IOReactorException ex2) {
                    if (this.reactorException == null) {
                        this.reactorException = ex2;
                    }
                }
            }
        }
        
        public IOReactorException getReactorException() {
            return this.reactorException;
        }

        public InterruptedIOException getInterruptedException() {
            return this.interruptedException;
        }
        
    }

}
