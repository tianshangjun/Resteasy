package org.jboss.resteasy.plugins.server.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;

import java.io.IOException;
import java.io.OutputStream;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class NettyHttpResponse implements HttpResponse
{
   private static final int EMPTY_CONTENT_LENGTH = 0;
   private int status = 200;
   private OutputStream os;
   private MultivaluedMap<String, Object> outputHeaders;
   private final ChannelHandlerContext ctx;
   private boolean committed;
   private boolean keepAlive;
   private ResteasyProviderFactory providerFactory;

   public NettyHttpResponse(ChannelHandlerContext ctx, boolean keepAlive, ResteasyProviderFactory providerFactory)
   {
      outputHeaders = new MultivaluedMapImpl<String, Object>();
      os = new ChunkOutputStream(this, ctx, 1000);
      this.ctx = ctx;
      this.keepAlive = keepAlive;
      this.providerFactory = providerFactory;
   }

   @Override
   public void setOutputStream(OutputStream os)
   {
      this.os = os;
   }

   @Override
   public int getStatus()
   {
      return status;
   }

   @Override
   public void setStatus(int status)
   {
      this.status = status;
   }

   @Override
   public MultivaluedMap<String, Object> getOutputHeaders()
   {
      return outputHeaders;
   }

   @Override
   public OutputStream getOutputStream() throws IOException
   {
      return os;
   }

   @Override
   public void addNewCookie(NewCookie cookie)
   {
      outputHeaders.add(javax.ws.rs.core.HttpHeaders.SET_COOKIE, cookie);
   }

   @Override
   public void sendError(int status) throws IOException
   {
      sendError(status, null);
   }

   @Override
   public void sendError(int status, String message) throws IOException
   {
      if (committed)
      {
         throw new IllegalStateException();
      }

      final HttpResponseStatus responseStatus;
      if (message != null)
      {
         responseStatus = new HttpResponseStatus(status, message);
         setStatus(status);
      }
      else
      {
         responseStatus = HttpResponseStatus.valueOf(status);
         setStatus(status);
      }
      io.netty.handler.codec.http.HttpResponse response = null;
      if (message != null)
      {
         ByteBuf byteBuf = ctx.alloc().buffer();
         byteBuf.writeBytes(message.getBytes());

         response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus, byteBuf);
      }
      else
      {
         response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus);

      }
      if (keepAlive)
      {
         // Add keep alive and content length if needed
         response.headers().add(Names.CONNECTION, Values.KEEP_ALIVE);
         if (message == null) response.headers().add(Names.CONTENT_LENGTH, 0);
         else response.headers().add(Names.CONTENT_LENGTH, message.getBytes().length);
      }
      ctx.writeAndFlush(response);
      committed = true;
   }

   @Override
   public boolean isCommitted()
   {
      return committed;
   }

   @Override
   public void reset()
   {
      if (committed)
      {
         throw new IllegalStateException("Already committed");
      }
      outputHeaders.clear();
      outputHeaders.clear();
   }

   public boolean isKeepAlive()
   {
      return keepAlive;
   }

   public DefaultHttpResponse getDefaultHttpResponse()
   {
       DefaultHttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(getStatus()));
       transformResponseHeaders(res);
       return res;
   }

   public DefaultHttpResponse getEmptyHttpResponse()
   {
       DefaultFullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(getStatus()));
       res.headers().add(Names.CONTENT_LENGTH, EMPTY_CONTENT_LENGTH);
       transformResponseHeaders(res);
       return res;
   }

   private void transformResponseHeaders(io.netty.handler.codec.http.HttpResponse res) {
       RestEasyHttpResponseEncoder.transformHeaders(this, res, providerFactory);
   }

   public void prepareChunkStream() {
      committed = true;
      DefaultHttpResponse response = getDefaultHttpResponse();
      HttpHeaders.setTransferEncodingChunked(response);
      ctx.write(response);
   }

   public void finish() throws IOException {
      os.flush();
      ChannelFuture future;
      if (isCommitted()) {
         // if committed this means the output stream was used.
         future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
      } else {
         future = ctx.writeAndFlush(getEmptyHttpResponse());
      }
      
      if(!isKeepAlive()) {
         future.addListener(ChannelFutureListener.CLOSE);
      }

   }


}
