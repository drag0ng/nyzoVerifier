package co.nyzo.verifier;

import co.nyzo.verifier.messages.*;
import co.nyzo.verifier.util.IpUtil;
import co.nyzo.verifier.util.SignatureUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class Message {

    private long timestamp;  // millisecond precision -- when the message is first generated
    private MessageType type;
    private MessageObject content;
    private byte[] sourceNodeIdentifier;  // the identifier of the node that created this message
    private byte[] sourceNodeSignature;   // the signature of all preceding parts
    private List<byte[]> recipientIdentifiers;  // the identifiers of all recipients
    private List<byte[]> recipientSignatures;   // the recipient signatures of the source-node signature
    private boolean valid;       // not serialized
    private boolean messageAlreadySeen;   // not serialized
    private byte[] sourceIpAddress;   // not serialized

    // This is the constructor for a new message originating from this system.
    public Message(MessageType type, MessageObject content) {
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.content = content;
        this.sourceNodeIdentifier = Verifier.getIdentifier();
        this.sourceNodeSignature = Verifier.sign(getBytesForSigning());
        this.recipientIdentifiers = new ArrayList<>();  // these are empty because no one has received the message yet
        this.recipientSignatures = new ArrayList<>();
        this.valid = true;
        this.messageAlreadySeen = true;
    }

    // This is the constructor for a message from another system.
    public Message(long timestamp, MessageType type, MessageObject content, byte[] sourceNodeIdentifier,
                   byte[] sourceNodeSignature, List<byte[]> recipientIdentifiers, List<byte[]> recipientSignatures,
                   byte[] sourceIpAddress) {
        this.timestamp = timestamp;
        this.type = type;
        this.content = content;
        this.sourceNodeIdentifier = sourceNodeIdentifier;
        this.sourceNodeSignature = sourceNodeSignature;
        this.sourceIpAddress = sourceIpAddress;

        // Verify the source signature.
        this.valid = SignatureUtil.signatureIsValid(sourceNodeSignature, getBytesForSigning(),
                sourceNodeIdentifier);
        System.out.println("message is valid: " + this.valid);

        // If the message is valid, verify the recipient signatures. Also, determine whether we have already seen this
        // message.
        this.messageAlreadySeen = this.valid && ByteUtil.arraysAreEqual(Verifier.getIdentifier(), sourceNodeIdentifier);
        this.recipientIdentifiers = new ArrayList<>();
        this.recipientSignatures = new ArrayList<>();
        if (this.valid) {
            int numberOfRecipients = Math.min(recipientIdentifiers.size(), recipientSignatures.size());
            for (int i = 0; i < numberOfRecipients; i++) {
                byte[] recipientIdentifier = recipientIdentifiers.get(i);
                byte[] recipientSignature = recipientSignatures.get(i);

                if (SignatureUtil.signatureIsValid(recipientSignature, sourceNodeSignature, recipientIdentifier)) {
                    this.recipientIdentifiers.add(recipientIdentifier);
                    this.recipientSignatures.add(recipientSignature);
                    if (ByteUtil.arraysAreEqual(Verifier.getIdentifier(), recipientIdentifier)) {
                        this.messageAlreadySeen = true;
                    }
                }
            }
        }

        if (!this.messageAlreadySeen) {
            this.recipientIdentifiers.add(Verifier.getIdentifier());
            this.recipientSignatures.add(Verifier.sign(sourceNodeSignature));
        }
    }

    public long getTimestamp() {
        return timestamp;
    }

    public MessageType getType() {
        return type;
    }

    public MessageObject getContent() {
        return content;
    }

    public byte[] getSourceNodeIdentifier() {
        return sourceNodeIdentifier;
    }

    public byte[] getSourceNodeSignature() {
        return sourceNodeSignature;
    }

    public boolean isValid() {
        return valid;
    }

    public byte[] getSourceIpAddress() {
        return sourceIpAddress;
    }

    public void sign(byte[] privateSeed) {
        this.sourceNodeIdentifier = KeyUtil.identifierForSeed(privateSeed);
        this.sourceNodeSignature = SignatureUtil.signBytes(getBytesForSigning(), privateSeed);
    }

    public static void broadcast(Message message) {

        // Send the message to up to six nodes.
        List<Node> mesh = NodeManager.getMesh();
        Random random = new Random();
        for (int i = 0; i < 6 && mesh.size() > 0; i++) {
            Node node = mesh.remove(random.nextInt(mesh.size()));
            fetch(IpUtil.addressAsString(node.getIpAddress()), node.getPort(), message, false, null);
        }
    }

    public static void fetch(String hostNameOrIp, int port, Message message, boolean retryIfFailed,
                             MessageCallback messageCallback) {

        new Thread(new Runnable() {
            @Override
            public void run() {
                Message response = null;
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(hostNameOrIp, port), 3000);

                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(message.getBytesForTransmission());

                    response = readFromStream(socket.getInputStream(), socket.getInetAddress().getAddress());
                    socket.close();
                } catch (Exception reportOnly) {
                    System.err.println("Exception connecting to " + hostNameOrIp + ":" + port + ": " +
                            reportOnly.getMessage());
                }

                if (messageCallback != null) {
                    if (response == null || !response.isValid()) {
                        if (retryIfFailed) {
                            System.out.println("RETRYING");
                            fetch(hostNameOrIp, port, message, false, messageCallback);
                        } else {
                            messageCallback.responseReceived(null);
                        }
                    } else {
                        messageCallback.responseReceived(response);
                    }
                }
            }
        }).start();
    }

    public static Message readFromStream(InputStream inputStream, byte[] sourceIpAddress) {

        byte[] response = getResponse(inputStream);
        System.out.println("response bytes are " + ByteUtil.arrayAsStringWithDashes(response));
        return fromBytes(response, sourceIpAddress);
    }

    private static byte[] getResponse(InputStream inputStream) {

        byte[] result = new byte[0];
        try {
            byte[] input = new byte[5000000];
            int size = inputStream.read(input);
            System.out.println("size is " + size);
            result = Arrays.copyOf(input, size);
        } catch (Exception ignore) { ignore.printStackTrace(); }

        return result;
    }

    public byte[] getBytesForSigning() {

        // Determine the size (timestamp, type, source-node identifier, content if present).
        int sizeBytes = FieldByteSize.timestamp + FieldByteSize.messageType + FieldByteSize.identifier;
        if (content != null) {
            sizeBytes += content.getByteSize();
        }

        // Make the buffer.
        byte[] result = new byte[sizeBytes];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        // Add the data.
        buffer.putLong(timestamp);
        buffer.putShort((short) type.getValue());
        if (content != null) {
            buffer.put(content.getBytes());
        }
        buffer.put(sourceNodeIdentifier);

        return result;
    }

    public byte[] getBytesForTransmission() {

        // Determine the size (timestamp, type, source-node identifier, source-node signature, recipient-node
        // identifiers and signatures, content if present).
        int sizeBytes = FieldByteSize.timestamp + FieldByteSize.messageType + (FieldByteSize.identifier +
                FieldByteSize.signature) * (recipientIdentifiers.size() + 1);
        if (content != null) {
            sizeBytes += content.getByteSize();
        }

        // Make the buffer.
        byte[] result = new byte[sizeBytes];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        // Add the data.
        buffer.putLong(timestamp);
        buffer.putShort((short) type.getValue());
        if (content != null) {
            buffer.put(content.getBytes());
        }
        buffer.put(sourceNodeIdentifier);
        buffer.put(sourceNodeSignature);
        if (recipientIdentifiers.size() > 0) {
            buffer.putInt(recipientIdentifiers.size());
            for (int i = 0; i < recipientIdentifiers.size(); i++) {  // these arrays are the same size
                buffer.put(recipientIdentifiers.get(i));
                buffer.put(recipientSignatures.get(i));
            }
        }

        System.out.println("full message on send: " + ByteUtil.arrayAsStringWithDashes(result));

        return result;
    }

    public static Message fromBytes(byte[] bytes, byte[] sourceIpAddress) {

        System.out.println("full message on receipt: " + ByteUtil.arrayAsStringWithDashes(bytes));

        Message message = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            long timestamp = buffer.getLong();
            int typeValue = buffer.getShort() & 0xffff;
            MessageType type = MessageType.forValue(typeValue);
            MessageObject content = processContent(type, buffer);

            byte[] sourceNodeIdentifier = new byte[FieldByteSize.identifier];
            buffer.get(sourceNodeIdentifier);
            byte[] sourceNodeSignature = new byte[FieldByteSize.signature];
            buffer.get(sourceNodeSignature);

            System.out.println("source identifier from message: " +
                    ByteUtil.arrayAsStringWithDashes(sourceNodeIdentifier));
            System.out.println("source signature from message: " + sourceNodeSignature);

            List<byte[]> recipientIdentifiers = new ArrayList<>();
            List<byte[]> recipientSignatures = new ArrayList<>();
            int numberOfRecipients = buffer.hasRemaining() ? buffer.getInt() : 0;
            System.out.println("number of recipients: " + numberOfRecipients);
            for (int i = 0; i < numberOfRecipients; i++) {
                byte[] recipientIdentifier = new byte[FieldByteSize.identifier];
                buffer.get(recipientIdentifier);
                recipientIdentifiers.add(recipientIdentifier);

                byte[] recipientSignature = new byte[FieldByteSize.signature];
                buffer.get(recipientSignature);
                recipientSignatures.add(recipientSignature);
            }

            message = new Message(timestamp, type, content, sourceNodeIdentifier, sourceNodeSignature,
                    recipientIdentifiers, recipientSignatures, sourceIpAddress);
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }

        return message;
    }

    private static MessageObject processContent(MessageType type, ByteBuffer buffer) {

        System.out.println("processing content of type " + type);

        MessageObject content = null;
        if (type == MessageType.NodeListResponse2) {
            content = NodeListResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.NodeJoin3) {
            content = NodeJoinMessage.fromByteBuffer(buffer);
        } else if (type == MessageType.NodeJoinResponse4) {
            content = NodeJoinResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.Transaction5) {
            content = Transaction.fromByteBuffer(buffer);
        } else if (type == MessageType.TransactionPoolResponse14) {
            content = TransactionPoolResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.HighestBlockFrozenResponse16) {
            content = HighestBlockFrozenResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.PingResponse201) {
            content = PingResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.UpdateResponse301) {
                content = UpdateResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.GenesisBlock500) {
            content = Block.fromByteBuffer(buffer);
        } else if (type == MessageType.GenesisBlockResponse501) {
            content = GenesisBlockAcknowledgement.fromByteBuffer(buffer);
        }

        return content;
    }

    @Override
    public String toString() {
        return "[Message: " + type + " (" + getContent() + ")]";
    }
}
