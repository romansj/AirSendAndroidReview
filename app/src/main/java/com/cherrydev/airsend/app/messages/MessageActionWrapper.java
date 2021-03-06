package com.cherrydev.airsend.app.messages;

import com.cherrydev.airsend.app.utils.DialogActionItemInterface;

public class MessageActionWrapper implements DialogActionItemInterface {
    private MessageAction messageAction;
    private String text;

    public MessageActionWrapper(MessageAction messageAction, String text) {
        this.messageAction = messageAction;
        this.text = text;
    }

    public MessageAction getMessageAction() {
        return messageAction;
    }

    public String getText() {
        return text;
    }
}
