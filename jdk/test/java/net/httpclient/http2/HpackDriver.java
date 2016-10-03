/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8153353
 * @modules java.httpclient/sun.net.httpclient.hpack
 * @key randomness
 * @compile/module=java.httpclient sun/net/httpclient/hpack/SpecHelper.java
 * @compile/module=java.httpclient sun/net/httpclient/hpack/TestHelper.java
 * @compile/module=java.httpclient sun/net/httpclient/hpack/BuffersTestingKit.java
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.BinaryPrimitivesTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.CircularBufferTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.DecoderTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.EncoderTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.HeaderTableTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.HuffmanTest
 * @run testng/othervm java.httpclient/sun.net.httpclient.hpack.TestHelper
 */
public class HpackDriver { }
