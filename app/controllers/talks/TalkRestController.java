package controllers.talks;

import static play.libs.Json.toJson;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import controllers.Secured;
import models.*;
import models.utils.TransformValidationErrors;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ObjectNode;
import play.Logger;
import play.data.Form;
import play.i18n.Messages;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.util.*;


@Security.Authenticated(Secured.class)
public class TalkRestController extends Controller {
	
	public static Result getById(Long idTalk) {
		Talk talk = Talk.find.byId(idTalk);
        User user = User.findByEmail(request().username());
        if (user.admin) {
            talk.vote = Vote.findVoteByUserAndTalk(user, talk);
        }
		return ok(toJson(talk));
	}
	
	public static Result get() {
		User user = User.findByEmail(request().username());
		List<Talk> talks = Talk.findBySpeaker(user);	
		return ok(toJson(talks));
	}

    public static Result getTalks(Long userId) {
        User user = User.find.byId(userId);
        List<Talk> talks = Talk.findBySpeaker(user);
        return ok(toJson(talks));
    }

    public static Result getTalksAccepted(Long userId) {
        User user = User.find.byId(userId);
        List<Talk> talks = Talk.findBySpeakerAndStatus(user, StatusTalk.ACCEPTE);
        return ok(toJson(talks));
    }

    public static Result all() {
        User user = User.findByEmail(request().username());
        if (!user.admin) {
            return unauthorized();
        }
        List<Talk> talks = Talk.find.all();
        for (Talk talk : talks) {
            talk.vote = Vote.findVoteByUserAndTalk(user, talk);
            if (VoteStatus.getVoteStatus() == VoteStatusEnum.CLOSED) {
                talk.moyenne = Vote.calculMoyenne(talk);
            }
        }
        return ok(toJson(talks));
    }


    public static Result save() {
        User user = User.findByEmail(request().username());
        Form<Talk> talkForm = form(Talk.class).bindFromRequest();

        if (talkForm.hasErrors()) {
            return badRequest(toJson(TransformValidationErrors.transform(talkForm.errors())));
        }

        Talk formTalk = talkForm.get();

        if (formTalk.id == null) {
            // Nouveau talk
            formTalk.speaker = user;
            if (Talk.findByTitle(formTalk.title) != null) {
                return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.talk.already.exist"))));
            }
            formTalk.save();
            updateTags(talkForm.data().get("tagsname"), formTalk);
        } else {
            // Mise à jour d'un talk
            Talk dbTalk = Talk.find.byId(formTalk.id);
            if (!formTalk.title.equals(dbTalk.title)
                    && Talk.findByTitle(formTalk.title) != null) {
                return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.talk.already.exist"))));
            }
            dbTalk.title = formTalk.title;
            dbTalk.description = formTalk.description;
            dbTalk.save();
            updateTags(talkForm.data().get("tagsname"), dbTalk);
        }


        // HTTP 204 en cas de succès (NO CONTENT)
        return noContent();
	}



    public static void updateTags(String tags, Talk dbTalk) {
        List<String> tagsList = Arrays.asList(tags.split(","));

        // suppression qui ne sont plus présent dans la nouvelle liste
        List<Tag> tagtmp = new ArrayList<Tag>(dbTalk.getTags());
        for (Tag tag : tagtmp) {
            if (!tagsList.contains(tag.nom)) {
                dbTalk.getTags().remove(tag);
            }
        }

        // ajout des tags ajoutés dans la liste
        for (String tag : tagsList) {
            if (!dbTalk.getTagsName().contains(tag)) {
                Tag dbTag = Tag.findByTagName(tag.toUpperCase());
                if (dbTag == null) {
                    dbTag = new Tag();
                    dbTag.nom = tag.toUpperCase();
                    dbTag.save();
                }
                Logger.debug("tags: = " + dbTag.id);
                dbTalk.getTags().add(dbTag);
            }
        }
        dbTalk.saveManyToManyAssociations("tags");
        dbTalk.update();
    }

    public static Result addTag(Long idTalk, String tags) {
        User user = User.findByEmail(request().username());
        Talk dbTalk = Talk.find.byId(idTalk);

        if (!user.admin && !user.id.equals(dbTalk.speaker.id)) {
            return unauthorized(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (dbTalk != null) {
            Logger.debug("addTags: = " + tags + " init tags " + dbTalk.getTagsName());
            updateTags(tags,dbTalk);
            Logger.debug("fin addTags: = " + dbTalk.getTagsName() + " size : " + dbTalk.getTags().size());
            return ok();
        } else {
            return notFound();
        }
    }
	
	public static Result delete(Long idTalk) {
        if (VoteStatus.getVoteStatus() != VoteStatusEnum.NOT_BEGIN) {
            return badRequest(toJson(TransformValidationErrors.transform(Messages.get("error.vote.begin"))));
        }

		Talk talk = Talk.find.byId(idTalk);
        for (Comment comment : talk.getComments()) {
            comment.delete();
        }

        List<Tag> tagtmp = new ArrayList<Tag>(talk.getTags());
        for (Tag tag : tagtmp) {
            talk.getTags().remove(tag);
        }
        talk.saveManyToManyAssociations("tags");
        talk.delete();
        // HTTP 204 en cas de succès (NO CONTENT)
        return noContent();
    }

    public static Result saveComment(Long idTalk) {
        User user = User.findByEmail(request().username());
        Talk talk = Talk.find.byId(idTalk);

        JsonNode node = request().body().asJson();
        String commentForm = null;
        if (node != null && node.get("comment") != null) {
            commentForm = node.get("comment").asText();
        } else {
            Map<String, List<String>> errors = new HashMap<String, List<String>>();
            errors.put("comment", Collections.singletonList(Messages.get("error.required")));
            return badRequest(toJson(errors));
        }

        if (!user.admin && !user.id.equals(talk.speaker.id)) {
            return unauthorized(toJson(TransformValidationErrors.transform("Action non autorisée")));
        }

        if (commentForm.length() > 0 && commentForm.length() <= 140) {
            Comment comment = new Comment();
            comment.author = user;
            comment.comment = commentForm;
            comment.talk = talk;
            comment.save();
            comment.sendMail();
        }
        return ok();
    }

    public static Result saveStatus(Long idTalk) {
        User user = User.findByEmail(request().username());
        Talk talk = Talk.find.byId(idTalk);
        if (!user.admin) {
            return unauthorized();
        }

        JsonNode node = request().body().asJson();

        StatusTalk newStatus = StatusTalk.fromValue(node.get("status").asText());
        if (talk.statusTalk != newStatus) {
            talk.statusTalk = newStatus;
            talk.save();
            if (talk.statusTalk != null) {
                talk.statusTalk.sendMail(talk, talk.speaker.email);
            }
        }

        return ok();
    }



    public static Result saveVote(Long idTalk, Integer note) {
        User user = User.findByEmail(request().username());
        Talk talk = Talk.find.byId(idTalk);
        if (!user.admin) {
            return unauthorized();
        }

        VoteStatusEnum voteStatus = VoteStatus.getVoteStatus();
        if (voteStatus != VoteStatusEnum.OPEN) {
            return unauthorized();
        }
        if (note == null || note < 1 || note > 5) {
            return badRequest();
        }

        Vote vote = Vote.findVoteByUserAndTalk(user, talk);
        if (vote == null) {
            vote = new Vote();
            vote.setUser(user);
            vote.setTalk(talk);
        }
        vote.setNote(note);
        vote.save();
        return ok();
    }

}