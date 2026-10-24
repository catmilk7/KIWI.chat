package com.vgu.client.database_dummy;

import com.vgu.client.models.messaging.Conversation_dummy;
import com.vgu.client.models.messaging.Message_dummy;
import com.vgu.client.models.identity.User_dummy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DummyDatabase {
    private static final List<User_dummy> users = new ArrayList<>();
    private static final List<Conversation_dummy> conversations = new ArrayList<>();
    private static User_dummy currentUser;

    static {
        // 1. Tạo danh sách Users mẫu
        User_dummy u1 = new User_dummy("u1", "@minh_vgu", "Minh Nguyễn", "Sinh viên CSE @ VGU. Đang làm đồ án JavaFX Chat App!", true);
        User_dummy u2 = new User_dummy("u2", "@lan_anh", "Lan Anh", "UI/UX Designer. Thích uống trà sữa và mèo.", true);
        User_dummy u3 = new User_dummy("u3", "@hoang_tran", "Hoàng Trần", "Backend Developer. Ngủ ngày cày đêm.", false);

        users.add(u1);
        users.add(u2);
        users.add(u3);
        currentUser = u1; // Mặc định đăng nhập là Minh Nguyễn

        // 2. Tạo các cuộc hội thoại mẫu
        Conversation_dummy c1 = new Conversation_dummy("c1", "VGU Project Team", true, 2, null);
        c1.addMessage(new Message_dummy("m1", u2, "Mọi người ơi, giao diện FXML mình đẩy lên Git rồi nhé!", LocalDateTime.now().minusMinutes(30)));
        c1.addMessage(new Message_dummy("m2", u3, "Ok để mình ghép phần Socket và Database vào.", LocalDateTime.now().minusMinutes(25)));
        c1.addMessage(new Message_dummy("m3", u1, "Tuyệt vời, chiều nay họp lúc 3h nha.", LocalDateTime.now().minusMinutes(10)));

        Conversation_dummy c2 = new Conversation_dummy("c2", "Lan Anh", false, 1, u2);
        c2.addMessage(new Message_dummy("m4", u1, "Lan Anh gửi giúp mình bộ icon được không?", LocalDateTime.now().minusHours(2)));
        c2.addMessage(new Message_dummy("m5", u2, "Mình tích hợp sẵn FontAwesome trong Ikonli rồi đó!", LocalDateTime.now().minusHours(1)));

        Conversation_dummy c3 = new Conversation_dummy("c3", "Hoàng Trần", false, 0, u3);
        c3.addMessage(new Message_dummy("m6", u3, "Tối nay chiến game không bro?", LocalDateTime.now().minusDays(1)));

        conversations.add(c1);
        conversations.add(c2);
        conversations.add(c3);
    }

    public static List<User_dummy> getAllUsers() {
        return users;
    }

    public static User_dummy getCurrentUser() {
        return currentUser;
    }

    public static void setCurrentUser(User_dummy user) {
        currentUser = user;
    }

    public static List<Conversation_dummy> getConversations(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(conversations);
        }
        String lower = query.toLowerCase().trim();
        return conversations.stream()
                .filter(c -> c.getTitle().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    public static void addMessage(String convId, String text) {
        for (Conversation_dummy c : conversations) {
            if (c.getId().equals(convId)) {
                c.addMessage(new Message_dummy(
                        UUID.randomUUID().toString(),
                        currentUser,
                        text,
                        LocalDateTime.now()
                ));
                break;
            }
        }
    }
}