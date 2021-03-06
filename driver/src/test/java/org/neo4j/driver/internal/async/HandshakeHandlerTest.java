/*
 * Copyright (c) 2002-2018 Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal.async;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;

import org.neo4j.driver.internal.async.inbound.ChunkDecoder;
import org.neo4j.driver.internal.async.inbound.InboundMessageDispatcher;
import org.neo4j.driver.internal.async.inbound.InboundMessageHandler;
import org.neo4j.driver.internal.async.inbound.MessageDecoder;
import org.neo4j.driver.internal.async.outbound.OutboundMessageHandler;
import org.neo4j.driver.internal.messaging.MessageFormat;
import org.neo4j.driver.internal.messaging.PackStreamMessageFormatV1;
import org.neo4j.driver.internal.messaging.PackStreamMessageFormatV2;
import org.neo4j.driver.internal.util.ErrorUtil;
import org.neo4j.driver.v1.Logging;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.SecurityException;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;

import static io.netty.buffer.Unpooled.copyInt;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.driver.internal.async.BoltProtocolUtil.HTTP;
import static org.neo4j.driver.internal.async.BoltProtocolUtil.NO_PROTOCOL_VERSION;
import static org.neo4j.driver.internal.async.BoltProtocolUtil.PROTOCOL_VERSION_1;
import static org.neo4j.driver.internal.async.BoltProtocolUtil.PROTOCOL_VERSION_2;
import static org.neo4j.driver.internal.async.ChannelAttributes.setMessageDispatcher;
import static org.neo4j.driver.internal.logging.DevNullLogging.DEV_NULL_LOGGING;
import static org.neo4j.driver.v1.util.TestUtil.await;

public class HandshakeHandlerTest
{
    private final EmbeddedChannel channel = new EmbeddedChannel();

    @Before
    public void setUp()
    {
        setMessageDispatcher( channel, new InboundMessageDispatcher( channel, DEV_NULL_LOGGING ) );
    }

    @After
    public void tearDown()
    {
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldFailGivenPromiseWhenExceptionCaught()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        RuntimeException cause = new RuntimeException( "Error!" );
        channel.pipeline().fireExceptionCaught( cause );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( ServiceUnavailableException e )
        {
            assertEquals( cause, e.getCause() );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    @Test
    public void shouldFailGivenPromiseWhenServiceUnavailableExceptionCaught()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        ServiceUnavailableException error = new ServiceUnavailableException( "Bad error" );
        channel.pipeline().fireExceptionCaught( error );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( ServiceUnavailableException e )
        {
            assertEquals( error, e );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    @Test
    public void shouldFailGivenPromiseWhenMultipleExceptionsCaught()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        RuntimeException error1 = new RuntimeException( "Error 1" );
        RuntimeException error2 = new RuntimeException( "Error 2" );
        channel.pipeline().fireExceptionCaught( error1 );
        channel.pipeline().fireExceptionCaught( error2 );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( ServiceUnavailableException e )
        {
            assertEquals( error1, e.getCause() );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );

        try
        {
            channel.checkException();
            fail( "Exception expected" );
        }
        catch ( RuntimeException e )
        {
            assertEquals( error2, e );
        }
    }

    @Test
    public void shouldUnwrapDecoderException()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        IOException cause = new IOException( "Error!" );
        channel.pipeline().fireExceptionCaught( new DecoderException( cause ) );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( ServiceUnavailableException e )
        {
            assertEquals( cause, e.getCause() );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    @Test
    public void shouldTranslateSSLHandshakeException()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        SSLHandshakeException error = new SSLHandshakeException( "Invalid certificate" );
        channel.pipeline().fireExceptionCaught( error );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( SecurityException e )
        {
            assertEquals( error, e.getCause() );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    @Test
    public void shouldSelectProtocolV1WhenServerSuggests()
    {
        testProtocolSelection( PROTOCOL_VERSION_1, PackStreamMessageFormatV1.class );
    }

    @Test
    public void shouldSelectProtocolV2WhenServerSuggests()
    {
        testProtocolSelection( PROTOCOL_VERSION_2, PackStreamMessageFormatV2.class );
    }

    @Test
    public void shouldFailGivenPromiseWhenServerSuggestsNoProtocol()
    {
        testFailure( NO_PROTOCOL_VERSION, "The server does not support any of the protocol versions" );
    }

    @Test
    public void shouldFailGivenPromiseWhenServerSuggestsHttp()
    {
        testFailure( HTTP, "Server responded HTTP" );
    }

    @Test
    public void shouldFailGivenPromiseWhenServerSuggestsUnknownProtocol()
    {
        testFailure( 42, "Protocol error" );
    }

    @Test
    public void shouldFailGivenPromiseWhenChannelInactive()
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        channel.pipeline().fireChannelInactive();

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( ServiceUnavailableException e )
        {
            assertEquals( ErrorUtil.newConnectionTerminatedError().getMessage(), e.getMessage() );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    private void testFailure( int serverSuggestedVersion, String expectedMessagePrefix )
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        HandshakeHandler handler = newHandler( handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        channel.pipeline().fireChannelRead( copyInt( serverSuggestedVersion ) );

        // handshake handler itself should be removed
        assertNull( channel.pipeline().get( HandshakeHandler.class ) );

        try
        {
            // promise should fail
            await( handshakeCompletedPromise );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( ClientException.class ) );
            assertThat( e.getMessage(), startsWith( expectedMessagePrefix ) );
        }

        // channel should be closed
        assertNull( await( channel.closeFuture() ) );
    }

    private void testProtocolSelection( int protocolVersion, Class<? extends MessageFormat> expectedMessageFormatClass )
    {
        ChannelPromise handshakeCompletedPromise = channel.newPromise();
        MemorizingChannelPipelineBuilder pipelineBuilder = new MemorizingChannelPipelineBuilder();
        HandshakeHandler handler = newHandler( pipelineBuilder, handshakeCompletedPromise );
        channel.pipeline().addLast( handler );

        channel.pipeline().fireChannelRead( copyInt( protocolVersion ) );

        // expected message format should've been used
        assertThat( pipelineBuilder.usedMessageFormat, instanceOf( expectedMessageFormatClass ) );

        // handshake handler itself should be removed
        assertNull( channel.pipeline().get( HandshakeHandler.class ) );

        // all inbound handlers should be set
        assertNotNull( channel.pipeline().get( ChunkDecoder.class ) );
        assertNotNull( channel.pipeline().get( MessageDecoder.class ) );
        assertNotNull( channel.pipeline().get( InboundMessageHandler.class ) );

        // all outbound handlers should be set
        assertNotNull( channel.pipeline().get( OutboundMessageHandler.class ) );

        // promise should be successful
        assertNull( await( handshakeCompletedPromise ) );
    }

    private static HandshakeHandler newHandler( ChannelPromise handshakeCompletedPromise )
    {
        return newHandler( new ChannelPipelineBuilderImpl(), handshakeCompletedPromise );
    }

    private static HandshakeHandler newHandler( ChannelPipelineBuilder pipelineBuilder, ChannelPromise handshakeCompletedPromise )
    {
        return new HandshakeHandler( pipelineBuilder, handshakeCompletedPromise, DEV_NULL_LOGGING );
    }

    private static class MemorizingChannelPipelineBuilder extends ChannelPipelineBuilderImpl
    {
        MessageFormat usedMessageFormat;

        @Override
        public void build( MessageFormat messageFormat, ChannelPipeline pipeline, Logging logging )
        {
            usedMessageFormat = messageFormat;
            super.build( messageFormat, pipeline, logging );
        }
    }
}
