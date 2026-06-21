package com.vertex.graph.service;

import com.vertex.graph.domain.Block;
import com.vertex.graph.domain.Follow;
import com.vertex.graph.domain.Friendship;
import com.vertex.graph.domain.FriendshipStatus;
import com.vertex.graph.events.SocialEvent;
import com.vertex.graph.events.SocialEventType;
import com.vertex.graph.exception.BadRequestException;
import com.vertex.graph.exception.ConflictException;
import com.vertex.graph.exception.NotFoundException;
import com.vertex.graph.repository.BlockRepository;
import com.vertex.graph.repository.FollowRepository;
import com.vertex.graph.repository.FriendshipRepository;
import com.vertex.graph.web.dto.CountsResponse;
import com.vertex.graph.web.dto.FriendStatus;
import com.vertex.graph.web.dto.FriendsPage;
import com.vertex.graph.web.dto.RelationshipResponse;
import com.vertex.graph.web.dto.UserPage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class GraphService {

    private static final int MAX_PAGE_SIZE = 100;

    private final FollowRepository follows;
    private final FriendshipRepository friendships;
    private final BlockRepository blocks;
    private final ApplicationEventPublisher events;

    public GraphService(FollowRepository follows, FriendshipRepository friendships, BlockRepository blocks,
                        ApplicationEventPublisher events) {
        this.follows = follows;
        this.friendships = friendships;
        this.blocks = blocks;
        this.events = events;
    }

    /**
     * Announce a relationship change. The event is published in-process inside the current
     * transaction; under the {@code kafka} profile {@code OutboxEventWriter} stages it to the outbox
     * before commit and {@code OutboxRelay} ships it to Kafka, otherwise it lands nowhere (and costs
     * nothing). Only call this on a real state change, never on an idempotent no-op, so we don't
     * notify twice for the same action.
     */
    private void announce(SocialEventType type, UUID actor, UUID recipient, UUID target) {
        events.publishEvent(SocialEvent.of(type, actor, recipient, target));
    }

    // --- Follows -------------------------------------------------------------

    @Transactional
    public void follow(UUID actor, UUID target) {
        requireNotSelf(actor, target);
        requireNotBlocked(actor, target);
        if (follows.existsByFollowerIdAndFolloweeId(actor, target)) {
            return; // idempotent
        }
        try {
            follows.save(new Follow(actor, target));
            announce(SocialEventType.FOLLOW, actor, target, null); // "actor followed you"
        } catch (DataIntegrityViolationException e) {
            // Concurrent follow won the unique constraint — the edge exists, and the winner already
            // announced it, so we stay quiet.
        }
    }

    @Transactional
    public void unfollow(UUID actor, UUID target) {
        follows.deleteByFollowerIdAndFolloweeId(actor, target); // no-op if not following
    }

    // --- Friendships ---------------------------------------------------------

    @Transactional
    public void sendFriendRequest(UUID actor, UUID target) {
        requireNotSelf(actor, target);
        requireNotBlocked(actor, target);

        String pairKey = Friendship.pairKey(actor, target);
        Optional<Friendship> existing = friendships.findByPairKey(pairKey);

        if (existing.isPresent()) {
            Friendship f = existing.get();
            if (f.getStatus() == FriendshipStatus.ACCEPTED) {
                return; // already friends
            }
            if (f.getRequesterId().equals(actor)) {
                return; // duplicate outgoing request — no-op
            }
            // The target already requested us first: crossing requests collapse into a friendship.
            // The original requester (the target) is the one who learns their request was accepted.
            f.accept();
            announce(SocialEventType.FRIEND_ACCEPT, actor, f.getRequesterId(), null);
            return;
        }

        try {
            friendships.save(new Friendship(actor, target));
            announce(SocialEventType.FRIEND_REQUEST, actor, target, null); // "actor wants to be friends"
        } catch (DataIntegrityViolationException e) {
            // A concurrent request created the row first; treat as a no-op.
        }
    }

    @Transactional
    public void acceptFriendRequest(UUID actor, UUID requester) {
        String pairKey = Friendship.pairKey(actor, requester);
        Friendship f = friendships.findByPairKey(pairKey)
                .orElseThrow(() -> new NotFoundException("no pending friend request"));

        if (f.getStatus() == FriendshipStatus.ACCEPTED) {
            return; // already friends — idempotent
        }
        if (f.getRequesterId().equals(actor)) {
            throw new ConflictException("cannot accept your own friend request");
        }
        // actor is the addressee of a PENDING request -> accept it. The requester gets told.
        f.accept();
        announce(SocialEventType.FRIEND_ACCEPT, actor, requester, null);
    }

    /** Cancel an outgoing request, reject an incoming one, or unfriend — all just drop the row. */
    @Transactional
    public void removeFriend(UUID actor, UUID target) {
        friendships.deleteByPairKey(Friendship.pairKey(actor, target)); // no-op if absent
    }

    // --- Blocks --------------------------------------------------------------

    @Transactional
    public void block(UUID actor, UUID target) {
        requireNotSelf(actor, target);
        if (!blocks.existsByBlockerIdAndBlockedId(actor, target)) {
            try {
                blocks.save(new Block(actor, target));
            } catch (DataIntegrityViolationException e) {
                // already blocked concurrently — idempotent
            }
        }
        // Block wins: drop any friendship and follows in either direction.
        friendships.deleteByPairKey(Friendship.pairKey(actor, target));
        follows.deleteBetween(actor, target);
    }

    @Transactional
    public void unblock(UUID actor, UUID target) {
        blocks.deleteByBlockerIdAndBlockedId(actor, target); // does not restore prior edges
    }

    // --- Reads ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public RelationshipResponse relationship(UUID actor, UUID target) {
        boolean following = follows.existsByFollowerIdAndFolloweeId(actor, target);
        boolean followedBy = follows.existsByFollowerIdAndFolloweeId(target, actor);
        boolean blocking = blocks.existsByBlockerIdAndBlockedId(actor, target);
        boolean blockedBy = blocks.existsByBlockerIdAndBlockedId(target, actor);

        FriendStatus friendStatus = friendships.findByPairKey(Friendship.pairKey(actor, target))
                .map(f -> {
                    if (f.getStatus() == FriendshipStatus.ACCEPTED) {
                        return FriendStatus.FRIENDS;
                    }
                    return f.getRequesterId().equals(actor)
                            ? FriendStatus.PENDING_OUTGOING
                            : FriendStatus.PENDING_INCOMING;
                })
                .orElse(FriendStatus.NONE);

        return new RelationshipResponse(target, following, followedBy, friendStatus, blocking, blockedBy);
    }

    @Transactional(readOnly = true)
    public CountsResponse counts(UUID userId) {
        return new CountsResponse(
                userId,
                follows.countByFolloweeId(userId),
                follows.countByFollowerId(userId),
                friendships.countAcceptedFor(userId));
    }

    @Transactional(readOnly = true)
    public FriendsPage listFriends(UUID actor, UUID cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        Limit fetch = Limit.of(pageSize + 1); // +1 to detect whether another page exists

        List<Friendship> rows = (cursor == null)
                ? friendships.findAcceptedFirstPage(actor, fetch)
                : friendships.findAcceptedAfter(actor, cursor, fetch);

        boolean hasMore = rows.size() > pageSize;
        List<Friendship> page = hasMore ? rows.subList(0, pageSize) : rows;

        List<UUID> items = page.stream().map(f -> f.other(actor)).toList();
        String nextCursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
        return new FriendsPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public UserPage listFollowers(UUID userId, UUID cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        Limit fetch = Limit.of(pageSize + 1);
        List<Follow> rows = (cursor == null)
                ? follows.followersFirstPage(userId, fetch)
                : follows.followersAfter(userId, cursor, fetch);
        return pageOf(rows, pageSize, Follow::getFollowerId);
    }

    @Transactional(readOnly = true)
    public UserPage listFollowing(UUID userId, UUID cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        Limit fetch = Limit.of(pageSize + 1);
        List<Follow> rows = (cursor == null)
                ? follows.followingFirstPage(userId, fetch)
                : follows.followingAfter(userId, cursor, fetch);
        return pageOf(rows, pageSize, Follow::getFolloweeId);
    }

    private UserPage pageOf(List<Follow> rows, int pageSize, Function<Follow, UUID> extractor) {
        boolean hasMore = rows.size() > pageSize;
        List<Follow> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<UUID> items = page.stream().map(extractor).toList();
        String nextCursor = hasMore ? page.get(page.size() - 1).getId().toString() : null;
        return new UserPage(items, nextCursor);
    }

    // --- Guards --------------------------------------------------------------

    private void requireNotSelf(UUID actor, UUID target) {
        if (actor.equals(target)) {
            throw new BadRequestException("cannot perform this action on yourself");
        }
    }

    private void requireNotBlocked(UUID actor, UUID target) {
        if (blocks.existsBetween(actor, target)) {
            // Fail silently: don't reveal that a block exists.
            throw new NotFoundException("user not available");
        }
    }
}
