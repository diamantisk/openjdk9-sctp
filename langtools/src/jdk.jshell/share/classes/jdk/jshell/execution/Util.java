/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.jshell.execution;

import jdk.jshell.spi.ExecutionEnv;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.sun.jdi.VirtualMachine;
import jdk.jshell.spi.ExecutionControl;


/**
 * Miscellaneous utility methods for setting-up implementations of
 * {@link ExecutionControl}. Particularly implementations with remote
 * execution.
 *
 * @author Jan Lahoda
 * @author Robert Field
 */
public class Util {

    // never instanciated
    private Util() {}

    /**
     * Create a composite {@link ExecutionControl.Generator} instance that, when
     * generating, will try each specified generator until successfully creating
     * an {@link ExecutionControl} instance, or, if all fail, will re-throw the
     * first exception.
     *
     * @param gec0 the first instance to try
     * @param gecs the second through Nth instance to try
     * @return the fail-over generator
     */
    public static ExecutionControl.Generator failOverExecutionControlGenerator(
            ExecutionControl.Generator gec0, ExecutionControl.Generator... gecs) {
        return (ExecutionEnv env) -> {
            Throwable thrown;
            try {
                return gec0.generate(env);
            } catch (Throwable ex) {
                thrown = ex;
            }
            for (ExecutionControl.Generator gec : gecs) {
                try {
                    return gec.generate(env);
                } catch (Throwable ignore) {
                    // only care about the first, and only if they all fail
                }
            }
            throw thrown;
        };
    }

    /**
     * Forward commands from the input to the specified {@link ExecutionControl}
     * instance, then responses back on the output.
     * @param ec the direct instance of {@link ExecutionControl} to process commands
     * @param in the command input
     * @param out the command response output
     */
    public static void forwardExecutionControl(ExecutionControl ec,
            ObjectInput in, ObjectOutput out) {
        new ExecutionControlForwarder(ec, in, out).commandLoop();
    }

    /**
     * Forward commands from the input to the specified {@link ExecutionControl}
     * instance, then responses back on the output.
     * @param ec the direct instance of {@link ExecutionControl} to process commands
     * @param inStream the stream from which to create the command input
     * @param outStream the stream that will carry any specified auxiliary channels (like
     *                  {@code System.out} and {@code System.err}), and the command response output.
     * @param outputStreamMap a map between names of additional streams to carry and setters
     *                        for the stream. Names starting with '$' are reserved for internal use.
     * @param inputStreamMap a map between names of additional streams to carry and setters
     *                       for the stream. Names starting with '$' are reserved for internal use.
     * @throws IOException if there are errors using the passed streams
     */
    public static void forwardExecutionControlAndIO(ExecutionControl ec,
            InputStream inStream, OutputStream outStream,
            Map<String, Consumer<OutputStream>> outputStreamMap,
            Map<String, Consumer<InputStream>> inputStreamMap) throws IOException {
        for (Entry<String, Consumer<OutputStream>> e : outputStreamMap.entrySet()) {
            e.getValue().accept(multiplexingOutputStream(e.getKey(), outStream));
        }

        ObjectOutputStream cmdOut = new ObjectOutputStream(multiplexingOutputStream("$command", outStream));
        PipeInputStream cmdInPipe = new PipeInputStream();
        Map<String, OutputStream> inputs = new HashMap<>();
        inputs.put("$command", cmdInPipe.createOutput());
        for (Entry<String, Consumer<InputStream>> e : inputStreamMap.entrySet()) {
            OutputStream inputSignal = multiplexingOutputStream("$" + e.getKey() + "-input-requested", outStream);
            PipeInputStream inputPipe = new PipeInputStream() {
                @Override protected void inputNeeded() throws IOException {
                    inputSignal.write('1');
                    inputSignal.flush();
                }
            };
            inputs.put(e.getKey(), inputPipe.createOutput());
            e.getValue().accept(inputPipe);
        }
        new DemultiplexInput(inStream, inputs, inputs.values()).start();
        ObjectInputStream cmdIn = new ObjectInputStream(cmdInPipe);

        forwardExecutionControl(ec, cmdIn, cmdOut);
    }

    static OutputStream multiplexingOutputStream(String label, OutputStream outputStream) {
        return new MultiplexingOutputStream(label, outputStream);
    }

    /**
     * Creates an ExecutionControl for given packetized input and output. The given InputStream
     * is de-packetized, and content forwarded to ObjectInput and given OutputStreams. The ObjectOutput
     * and values read from the given InputStream are packetized and sent to the given OutputStream.
     *
     * @param input the packetized input stream
     * @param output the packetized output stream
     * @param outputStreamMap a map between stream names and the output streams to forward.
     *                        Names starting with '$' are reserved for internal use.
     * @param inputStreamMap a map between stream names and the input streams to forward.
     *                       Names starting with '$' are reserved for internal use.
     * @param factory to create the ExecutionControl from ObjectInput and ObjectOutput.
     * @return the created ExecutionControl
     * @throws IOException if setting up the streams raised an exception
     */
    public static ExecutionControl remoteInputOutput(InputStream input, OutputStream output,
            Map<String, OutputStream> outputStreamMap, Map<String, InputStream> inputStreamMap,
            BiFunction<ObjectInput, ObjectOutput, ExecutionControl> factory) throws IOException {
        Map<String, OutputStream> augmentedStreamMap = new HashMap<>(outputStreamMap);
        ObjectOutput commandOut = new ObjectOutputStream(Util.multiplexingOutputStream("$command", output));
        for (Entry<String, InputStream> e : inputStreamMap.entrySet()) {
            InputStream  in = e.getValue();
            OutputStream inTarget = Util.multiplexingOutputStream(e.getKey(), output);
            augmentedStreamMap.put("$" + e.getKey() + "-input-requested", new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    //value ignored, just a trigger to read from the input
                    inTarget.write(in.read());
                }
            });
        }
        PipeInputStream commandIn = new PipeInputStream();
        OutputStream commandInTarget = commandIn.createOutput();
        augmentedStreamMap.put("$command", commandInTarget);
        new DemultiplexInput(input, augmentedStreamMap, Arrays.asList(commandInTarget)).start();
        return factory.apply(new ObjectInputStream(commandIn), commandOut);
    }

    /**
     * Monitor the JDI event stream for {@link com.sun.jdi.event.VMDeathEvent}
     * and {@link com.sun.jdi.event.VMDisconnectEvent}. If encountered, invokes
     * {@code unbiddenExitHandler}.
     *
     * @param vm the virtual machine to check
     * @param unbiddenExitHandler the handler, which will accept the exit
     * information
     */
    public static void detectJDIExitEvent(VirtualMachine vm, Consumer<String> unbiddenExitHandler) {
        if (vm.canBeModified()) {
            new JDIEventHandler(vm, unbiddenExitHandler).start();
        }
    }

}
