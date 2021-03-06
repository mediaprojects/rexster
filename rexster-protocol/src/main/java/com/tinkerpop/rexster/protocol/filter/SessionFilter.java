package com.tinkerpop.rexster.protocol.filter;

import com.tinkerpop.rexster.client.RexProException;
import com.tinkerpop.rexster.protocol.EngineController;
import com.tinkerpop.rexster.protocol.RexProSession;
import com.tinkerpop.rexster.protocol.RexProSessions;
import com.tinkerpop.rexster.protocol.msg.ErrorResponseMessage;
import com.tinkerpop.rexster.protocol.msg.MessageTokens;
import com.tinkerpop.rexster.protocol.msg.MessageUtil;
import com.tinkerpop.rexster.protocol.msg.RexProMessage;
import com.tinkerpop.rexster.protocol.msg.ScriptRequestMessage;
import com.tinkerpop.rexster.protocol.msg.SessionRequestMessage;
import com.tinkerpop.rexster.protocol.msg.SessionResponseMessage;
import com.tinkerpop.rexster.server.RexsterApplication;
import org.apache.log4j.Logger;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

import java.io.IOException;
import java.util.List;

/**
 * Processes session request messages or forwards through sessionless script request messages.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @author Blake Eggleston (bdeggleston.github.com)
 */
public class SessionFilter extends BaseFilter {

    private static final Logger logger = Logger.getLogger(SessionFilter.class);

    private final RexsterApplication rexsterApplication;

    public SessionFilter(final RexsterApplication rexsterApplication) {
        this.rexsterApplication = rexsterApplication;
    }

    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        final RexProMessage message = ctx.getMessage();

        // shortcut all the session stuff
        //TODO: move session detection, validation, and error responses into
        if (message instanceof ScriptRequestMessage)  {
            return ctx.getInvokeAction();
        }

        // everything from here forward is about session creation and checking
        if (message instanceof SessionRequestMessage) {
            final SessionRequestMessage specificMessage = (SessionRequestMessage) message;
            try {
                specificMessage.validateMetaData();
            } catch (Exception e) {
                logger.error(e);
                ctx.write(
                    MessageUtil.createErrorResponse(
                        specificMessage.Request,
                        RexProMessage.EMPTY_SESSION_AS_BYTES,
                        ErrorResponseMessage.INVALID_MESSAGE_ERROR,
                        e.toString()
                    )
                );
            }

            if (specificMessage.metaGetKillSession()) {
                //destroy the session
                RexProSessions.destroySession(specificMessage.sessionAsUUID().toString());
                ctx.write(MessageUtil.createEmptySession(specificMessage.Request));

            } else {
                final EngineController engineController = EngineController.getInstance();
                final List<String> engineLanguages = engineController.getAvailableEngineLanguages();

                final SessionResponseMessage responseMessage = MessageUtil.createNewSession(
                        specificMessage.Request, engineLanguages);

                // construct a session with the right channel
                if(!RexProSessions.hasSessionKey(responseMessage.sessionAsUUID().toString())) {
                    RexProSession session = RexProSessions.createSession(
                        responseMessage.sessionAsUUID().toString(),
                        this.rexsterApplication,
                        specificMessage.Channel
                    );

                    //configure the graph object
                    if (specificMessage.metaGetGraphName() != null) {
                        try {
                            session.setGraphObj(specificMessage.metaGetGraphName(), specificMessage.metaGetGraphObjName());
                        } catch (RexProException ex) {
                            //graph config problem
                            ctx.write(
                                MessageUtil.createErrorResponse(
                                    message.Request, RexProMessage.EMPTY_SESSION_AS_BYTES,
                                    ErrorResponseMessage.GRAPH_CONFIG_ERROR,
                                    ex.toString()
                                )
                            );

                            return ctx.getStopAction();
                        }
                    }
                }
                ctx.write(responseMessage);

            }

            // nothing left to do...session was created
            return ctx.getStopAction();
        }

        if (!message.hasSession()) {
            // there is no session to this message...that's a problem
            ctx.write(
                MessageUtil.createErrorResponse(
                    message.Request, RexProMessage.EMPTY_SESSION_AS_BYTES,
                    ErrorResponseMessage.INVALID_SESSION_ERROR,
                    MessageTokens.ERROR_SESSION_NOT_SPECIFIED
                )
            );

            return ctx.getStopAction();
        }

        if (!RexProSessions.hasSessionKey(message.sessionAsUUID().toString())) {
            // the message is assigned a session that does not exist on the server
            ctx.write(
                MessageUtil.createErrorResponse(
                    message.Request, RexProMessage.EMPTY_SESSION_AS_BYTES,
                    ErrorResponseMessage.INVALID_SESSION_ERROR,
                    MessageTokens.ERROR_SESSION_INVALID
                )
            );

            return ctx.getStopAction();
        }

        return ctx.getInvokeAction();
    }
}
