package org.terasology.logic.characters;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.shapes.CapsuleShape;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.componentSystem.UpdateSubscriberSystem;
import org.terasology.components.world.LocationComponent;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.In;
import org.terasology.entitySystem.ReceiveEvent;
import org.terasology.entitySystem.RegisterSystem;
import org.terasology.entitySystem.RegisterMode;
import org.terasology.entitySystem.event.AddComponentEvent;
import org.terasology.entitySystem.event.RemovedComponentEvent;
import org.terasology.game.Timer;
import org.terasology.logic.characters.bullet.BulletCharacterMover;
import org.terasology.logic.players.LocalPlayer;
import org.terasology.logic.players.LocalPlayerComponent;
import org.terasology.physics.BulletPhysics;
import org.terasology.physics.CollisionGroup;
import org.terasology.utilities.collection.CircularBuffer;
import org.terasology.world.WorldProvider;

import javax.vecmath.Vector3f;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Immortius
 */
@RegisterSystem(RegisterMode.CLIENT)
public class ClientCharacterPredictionSystem implements UpdateSubscriberSystem {
    private static final Logger logger = LoggerFactory.getLogger(ClientCharacterPredictionSystem.class);
    private static final int BUFFER_SIZE = 128;

    @In
    private Timer timer;

    @In
    private BulletPhysics physics;

    @In
    private WorldProvider worldProvider;

    @In
    private LocalPlayer localPlayer;

    private CharacterMover characterMover;
    private Map<EntityRef, CircularBuffer<CharacterStateEvent>> playerStates = Maps.newHashMap();
    private Deque<CharacterMoveInputEvent> inputs = Queues.newArrayDeque();
    private CharacterStateEvent predictedState;
    private CharacterStateEvent authoritiveState;

    @Override
    public void initialise() {
        characterMover = new BulletCharacterMover(worldProvider);
    }

    @Override
    public void shutdown() {
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class})
    public void onCreate(final AddComponentEvent event, final EntityRef entity) {
        LocationComponent location = entity.getComponent(LocationComponent.class);
        CharacterMovementComponent movementComp = entity.getComponent(CharacterMovementComponent.class);
        float height = (movementComp.height - 2 * movementComp.radius) * location.getWorldScale();
        float width = movementComp.radius * location.getWorldScale();
        ConvexShape capsule = new CapsuleShape(width, height);
        capsule.setMargin(0.1f);
        movementComp.collider = physics.createCollider(location.getWorldPosition(), capsule, Lists.<CollisionGroup>newArrayList(movementComp.collisionGroup), movementComp.collidesWith, CollisionFlags.CHARACTER_OBJECT);
        movementComp.collider.setUserPointer(entity);

        CircularBuffer<CharacterStateEvent> stateBuffer = CircularBuffer.create(BUFFER_SIZE);
        stateBuffer.add(createInitialState(entity));
        playerStates.put(entity, stateBuffer);
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class, LocationComponent.class})
    public void onLocalPlayerCreated(final AddComponentEvent event, final EntityRef entity) {
        predictedState = createInitialState(entity);
        authoritiveState = predictedState;
        playerStates.remove(entity);
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class})
    public void onDestroy(final RemovedComponentEvent event, final EntityRef entity) {
        CharacterMovementComponent comp = entity.getComponent(CharacterMovementComponent.class);
        if (comp.collider != null) {
            physics.removeCollider(comp.collider);
        }
        playerStates.remove(entity);
    }

    @ReceiveEvent(components = {CharacterMovementComponent.class, LocationComponent.class})
    public void onCharacterStateReceived(CharacterStateEvent state, EntityRef entity) {
        if (entity.equals(localPlayer.getCharacterEntity())) {

            logger.trace("Received new state, sequence number: {}, buffered input size {}", state.getSequenceNumber(), inputs.size());

            authoritiveState = state;
            Iterator<CharacterMoveInputEvent> inputIterator = inputs.iterator();
            CharacterStateEvent newState = authoritiveState;
            while (inputIterator.hasNext()) {
                CharacterMoveInputEvent input = inputIterator.next();
                if (input.getSequenceNumber() <= state.getSequenceNumber()) {
                    inputIterator.remove();
                } else {
                    newState = stepState(input, newState, entity);
                }
            }
            logger.trace("Resultant input size {}", inputs.size());
            CharacterStateEvent.setToState(entity, newState);
            // TODO: soft correct predicted state
            predictedState = newState;
        } else {
            playerStates.get(entity).add(state);
        }
    }


    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class, LocationComponent.class})
    public void onPlayerInput(CharacterMoveInputEvent input, EntityRef entity) {
        inputs.add(input);
        CharacterStateEvent newState = stepState(input, predictedState, entity);
        predictedState = newState;

        CharacterStateEvent.setToState(entity, newState);
    }

    private CharacterStateEvent createInitialState(EntityRef entity) {
        LocationComponent location = entity.getComponent(LocationComponent.class);
        return new CharacterStateEvent(timer.getTimeInMs(), 0, location.getWorldPosition(), location.getWorldRotation(), new Vector3f(), 0, 0, MovementMode.WALKING, false);
    }

    private CharacterStateEvent stepState(CharacterMoveInputEvent input, CharacterStateEvent lastState, EntityRef entity) {
        return characterMover.step(lastState, input, entity);
    }

    @Override
    public void update(float delta) {
        long renderTime = timer.getTimeInMs() - ServerCharacterPredictionSystem.RENDER_DELAY;
        for (Map.Entry<EntityRef, CircularBuffer<CharacterStateEvent>> entry : playerStates.entrySet()) {
            CharacterStateEvent previous = null;
            CharacterStateEvent next = null;
            for (CharacterStateEvent state : entry.getValue()) {
                if (state.getTime() <= renderTime) {
                    previous = state;
                } else {
                    next = state;
                    break;
                }
            }
            if (previous != null) {
                if (next != null) {
                    CharacterStateEvent.setToInterpolateState(entry.getKey(), previous, next, renderTime);
                } else {
                    CharacterStateEvent.setToExtrapolateState(entry.getKey(), previous, renderTime);
                }
            }
        }
    }
}