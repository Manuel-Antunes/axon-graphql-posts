package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

@Entity
@Table(name = "readers")
@PrimaryKeyJoinColumn(name = "id")
@SQLDelete(sql = "update readers set id = id where id = ?")
public class Reader extends User {
    protected Reader() {
    }

    Reader(UserRegisteredEvent event) {
        super(event);
    }
}
