/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

 /* Code based on unix repo of jdk.sctp with minor modifications where needed by Diamantis Kyriakakis (diamants.kiriakakis@gmail.com) */

#ifndef SUN_NIO_CH_SCTP_H
#define SUN_NIO_CH_SCTP_H

#define _XPG4_2
#define __EXTENSIONS__
#include "ws2sctp.h"
#include "jni.h"

/* Current Solaris headers don't comply with draft rfc */
#ifndef SCTP_EOF
#define SCTP_EOF MSG_EOF
#endif

#ifndef SCTP_UNORDERED
#define SCTP_UNORDERED MSG_UNORDERED
#endif

/* The current version of the socket API extension shipped with Solaris does
 * not define the following options that the Java API (optionally) supports */
#ifndef SCTP_EXPLICIT_EOR
#define SCTP_EXPLICIT_EOR -1
#endif
#ifndef SCTP_FRAGMENT_INTERLEAVE
#define SCTP_FRAGMENT_INTERLEAVE -1
#endif
#ifndef SCTP_SET_PEER_PRIMARY_ADDR
#define SCTP_SET_PEER_PRIMARY_ADDR -1
#endif

/* Function types to support dynamic linking of socket API extension functions
 * for SCTP. This is so that there is no linkage depandancy during build or
 * runtime for libsctp.*/
typedef int sctp_getladdrs_func(int sock, sctp_assoc_t id, void **addrs);
typedef int sctp_freeladdrs_func(void* addrs);
typedef int sctp_getpaddrs_func(int sock, sctp_assoc_t id, void **addrs);
typedef int sctp_freepaddrs_func(void *addrs);
typedef int sctp_bindx_func(int sock, void *addrs, int addrcnt, int flags);
typedef int sctp_peeloff_func(int sock, sctp_assoc_t id);


sctp_getladdrs_func* nio_sctp_getladdrs;
sctp_freeladdrs_func* nio_sctp_freeladdrs;
sctp_getpaddrs_func* nio_sctp_getpaddrs;
sctp_freepaddrs_func* nio_sctp_freepaddrs;
sctp_bindx_func* nio_sctp_bindx;
sctp_peeloff_func* nio_sctp_peeloff;

jboolean loadSocketExtensionFuncs(JNIEnv* env);

#endif /* !SUN_NIO_CH_SCTP_H */
