package app

import jp.sf.amateras.scalatra.forms._

import service._
import IssuesService._
import util.{CollaboratorsAuthenticator, ReferrerAuthenticator, ReadableUsersAuthenticator}
import org.scalatra.Ok

class IssuesController extends IssuesControllerBase
  with IssuesService with RepositoryService with AccountService with LabelsService with MilestonesService
  with ReadableUsersAuthenticator with ReferrerAuthenticator with CollaboratorsAuthenticator

trait IssuesControllerBase extends ControllerBase {
  self: IssuesService with RepositoryService with LabelsService with MilestonesService
    with ReadableUsersAuthenticator with ReferrerAuthenticator with CollaboratorsAuthenticator =>

  case class IssueCreateForm(title: String, content: Option[String],
    assignedUserName: Option[String], milestoneId: Option[Int], labelNames: Option[String])

  case class IssueEditForm(title: String, content: Option[String])

  case class CommentForm(issueId: Int, content: String)

  val issueCreateForm = mapping(
      "title"            -> trim(label("Title", text(required))),
      "content"          -> trim(optional(text())),
      "assignedUserName" -> trim(optional(text())),
      "milestoneId"      -> trim(optional(number())),
      "labelNames"       -> trim(optional(text()))
    )(IssueCreateForm.apply)

  val issueEditForm = mapping(
      "title"   -> trim(label("Title", text(required))),
      "content" -> trim(optional(text()))
    )(IssueEditForm.apply)

  val commentForm = mapping(
      "issueId" -> label("Issue Id", number()),
      "content" -> trim(label("Comment", text(required)))
    )(CommentForm.apply)

  get("/:owner/:repository/issues")(referrersOnly {
    searchIssues("all")
  })

  get("/:owner/:repository/issues/assigned/:userName")(referrersOnly {
    searchIssues("assigned")
  })

  get("/:owner/:repository/issues/created_by/:userName")(referrersOnly {
    searchIssues("created_by")
  })

  get("/:owner/:repository/issues/:id")(referrersOnly {
    val owner = params("owner")
    val repository = params("repository")
    val issueId = params("id")

    getIssue(owner, repository, issueId) map {
      issues.html.issue(
          _,
          getComments(owner, repository, issueId.toInt),
          getIssueLabels(owner, repository, issueId.toInt),
          (getCollaborators(owner, repository) :+ owner).sorted,
          getMilestones(owner, repository),
          getLabels(owner, repository),
          hasWritePermission(owner, repository, context.loginAccount),
          getRepository(owner, repository, baseUrl).get)
    } getOrElse NotFound
  })

  get("/:owner/:repository/issues/new")(readableUsersOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl).map {
      issues.html.create(
        (getCollaborators(owner, repository) :+ owner).sorted,
        getMilestones(owner, repository),
        getLabels(owner, repository),
        _)
    } getOrElse NotFound
  })

  post("/:owner/:repository/issues/new", issueCreateForm)(readableUsersOnly { form =>
    val owner      = params("owner")
    val repository = params("repository")
    val writable   = hasWritePermission(owner, repository, context.loginAccount)

    val issueId = createIssue(owner, repository, context.loginAccount.get.userName, form.title, form.content,
      if(writable) form.assignedUserName else None,
      if(writable) form.milestoneId else None)

    if(writable){
      form.labelNames.map { value =>
        val labels = getLabels(owner, repository)
        value.split(",").foreach { labelName =>
          labels.find(_.labelName == labelName).map { label =>
            registerIssueLabel(owner, repository, issueId, label.labelId)
          }
        }
      }
    }

    redirect("/%s/%s/issues/%d".format(owner, repository, issueId))
  })

  ajaxPost("/:owner/:repository/issues/edit/:id", issueEditForm)(readableUsersOnly { form =>
    val owner      = params("owner")
    val repository = params("repository")
    val issueId    = params("id").toInt
    val writable   = hasWritePermission(owner, repository, context.loginAccount)

    getIssue(owner, repository, issueId.toString).map { issue =>
      if(writable || issue.openedUserName == context.loginAccount.get.userName){
        updateIssue(owner, repository, issueId, form.title, form.content)
        redirect("/%s/%s/issues/_data/%d".format(owner, repository, issueId))
      } else {
        Unauthorized
      }
    } getOrElse NotFound
  })

  // TODO repository checking
  post("/:owner/:repository/issue_comments/new", commentForm)(readableUsersOnly { form =>
    val owner = params("owner")
    val repository = params("repository")
    val action = params.get("action") filter { action =>
      updateClosed(owner, repository, form.issueId, if(action == "close") true else false) > 0
    }

    redirect("/%s/%s/issues/%d#comment-%d".format(owner, repository, form.issueId,
        createComment(owner, repository, context.loginAccount.get.userName, form.issueId, form.content, action)))
  })

  // TODO repository checking
  ajaxPost("/:owner/:repository/issue_comments/edit/:id", commentForm)(readableUsersOnly { form =>
    val owner      = params("owner")
    val repository = params("repository")
    val commentId  = params("id").toInt
    val writable   = hasWritePermission(owner, repository, context.loginAccount)

    getComment(commentId.toString).map { comment =>
      if(writable || comment.commentedUserName == context.loginAccount.get.userName){
        updateComment(commentId, form.content)
        redirect("/%s/%s/issue_comments/_data/%d".format(owner, repository, commentId))
      } else {
        Unauthorized
      }
    } getOrElse NotFound
  })

  // TODO Authenticator
  ajaxGet("/:owner/:repository/issues/_data/:id"){
    getIssue(params("owner"), params("repository"), params("id")) map { x =>
      params.get("dataType") collect {
        case t if t == "html" => issues.html.editissue(
            x.title, x.content, x.issueId, x.userName, x.repositoryName)
      } getOrElse {
        contentType = formats("json")
        org.json4s.jackson.Serialization.write(
            Map("title"   -> x.title,
                "content" -> view.Markdown.toHtml(x.content getOrElse "No description given.",
                    getRepository(x.userName, x.repositoryName, baseUrl).get, false, true, true)
            ))
      }
    } getOrElse NotFound
  }

  // TODO Authenticator
  ajaxGet("/:owner/:repository/issue_comments/_data/:id"){
    getComment(params("id")) map { x =>
      params.get("dataType") collect {
        case t if t == "html" => issues.html.editcomment(
            x.content, x.commentId, x.userName, x.repositoryName)
      } getOrElse {
        contentType = formats("json")
        org.json4s.jackson.Serialization.write(
            Map("content" -> view.Markdown.toHtml(x.content,
                getRepository(x.userName, x.repositoryName, baseUrl).get, false, true, true)
            ))
      }
    } getOrElse NotFound
  }

  ajaxPost("/:owner/:repository/issues/:id/label/new")(collaboratorsOnly {
    val owner = params("owner")
    val repository = params("repository")
    val issueId = params("id").toInt

    registerIssueLabel(owner, repository, issueId, params("labelId").toInt)

    issues.html.labellist(getIssueLabels(owner, repository, issueId))
  })

  ajaxPost("/:owner/:repository/issues/:id/label/delete")(collaboratorsOnly {
    val owner = params("owner")
    val repository = params("repository")
    val issueId = params("id").toInt

    deleteIssueLabel(owner, repository, issueId, params("labelId").toInt)

    issues.html.labellist(getIssueLabels(owner, repository, issueId))
  })

  ajaxPost("/:owner/:repository/issues/:id/assign")(collaboratorsOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val issueId    = params("id").toInt

    params.get("assignedUserName") match {
      case None                     => updateAssignedUserName(owner, repository, issueId, None)
      case Some(x) if(x.trim == "") => updateAssignedUserName(owner, repository, issueId, None)
      case Some(userName)           => updateAssignedUserName(owner, repository, issueId, Some(userName))
    }
    Ok("updated")
  })

  ajaxPost("/:owner/:repository/issues/:id/milestone")(collaboratorsOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val issueId    = params("id").toInt

    params.get("milestoneId") match {
      case None                     => updateMilestoneId(owner, repository, issueId, None)
      case Some(x) if(x.trim == "") => updateMilestoneId(owner, repository, issueId, None)
      case Some(milestoneId)        => updateMilestoneId(owner, repository, issueId, Some(milestoneId.toInt))
    }
    Ok("updated")
  })

  private def searchIssues(filter: String) = {
    val owner      = params("owner")
    val repository = params("repository")
    val userName   = if(filter != "all") Some(params("userName")) else None
    val sessionKey = "%s/%s/issues".format(owner, repository)

    val page = try {
      val i = params.getOrElse("page", "1").toInt
      if(i <= 0) 1 else i
    } catch {
      case e: NumberFormatException => 1
    }

    // retrieve search condition
    val condition = if(request.getQueryString == null){
      session.get(sessionKey).getOrElse(IssueSearchCondition()).asInstanceOf[IssueSearchCondition]
    } else IssueSearchCondition(request)

    session.put(sessionKey, condition)

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      issues.html.list(
        searchIssue(owner, repository, condition, filter, userName, (page - 1) * IssueLimit, IssueLimit),
        page,
        getLabels(owner, repository),
        getMilestones(owner, repository).filter(_.closedDate.isEmpty),
        countIssue(owner, repository, condition.copy(state = "open"), filter, userName),
        countIssue(owner, repository, condition.copy(state = "closed"), filter, userName),
        countIssue(owner, repository, condition, "all", None),
        context.loginAccount.map(x => countIssue(owner, repository, condition, "assigned", Some(x.userName))),
        context.loginAccount.map(x => countIssue(owner, repository, condition, "created_by", Some(x.userName))),
        countIssueGroupByLabels(owner, repository, condition, filter, userName),
        condition,
        filter,
        repositoryInfo,
        hasWritePermission(owner, repository, context.loginAccount))

    } getOrElse NotFound
  }

}
