package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entidade JPA do read model (tabela {@code posts} no SQLite). Detalhe de infraestrutura: a aplicação
 * nunca a vê, só {@code PostView}.
 * <p>
 * É um JavaBean de propósito — getters e setters, construtor sem argumentos. Não é um capricho de estilo:
 * é o que o JPA exige e o que permite ao {@code PostEntityMapper} gerar a conversão nos dois sentidos
 * sem uma linha escrita à mão.
 */
@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(nullable = false, length = 200)
    private String title;

    // TEXT em vez de @Lob: o driver sqlite-jdbc não implementa a API de CLOB do JDBC
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private long version;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
