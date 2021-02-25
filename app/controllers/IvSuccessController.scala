/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import config.FrontendAppConfig
import connectors.TaxEnrolmentsConnector
import controllers.actions._
import handlers.ErrorHandler
import javax.inject.Inject
import models.{NormalMode, TaxEnrolmentsRequest}
import pages.{IdentifierPage, IsAgentManagingTrustPage}
import play.api.Logging
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Result}
import services.{RelationshipEstablishment, RelationshipFound, RelationshipNotFound}
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendBaseController
import utils.Session
import views.html.IvSuccessView

import scala.concurrent.{ExecutionContext, Future}

class IvSuccessController @Inject()(
                                     override val messagesApi: MessagesApi,
                                     identify: IdentifierAction,
                                     getData: DataRetrievalAction,
                                     requireData: DataRequiredAction,
                                     val controllerComponents: MessagesControllerComponents,
                                     relationshipEstablishment: RelationshipEstablishment,
                                     taxEnrolmentsConnector: TaxEnrolmentsConnector,
                                     view: IvSuccessView,
                                     errorHandler: ErrorHandler
                                   )(implicit ec: ExecutionContext,
                                     val config: FrontendAppConfig)
  extends FrontendBaseController with I18nSupport
                                    with AuthPartialFunctions with Logging {

  def onPageLoad(): Action[AnyContent] = (identify andThen getData andThen requireData).async {
    implicit request =>

      // get has enrolled already and handle first time None?

      request.userAnswers.get(IdentifierPage).map { identifier =>

        def onRelationshipFound: Future[Result] = {
          taxEnrolmentsConnector.enrol(TaxEnrolmentsRequest(identifier)) map { _ =>

            // request.userAnswers.set(HasEnrolled, true)
            // Save this to mongo // write it out

            val isAgentManagingTrust: Boolean = request.userAnswers.get(IsAgentManagingTrustPage).getOrElse(false)

            logger.info(s"[Claiming][Session ID: ${Session.id(hc)}] successfully enrolled $identifier to users" +
              s"credential after passing Trust IV, user can now maintain the trust")

            Ok(view(isAgentManagingTrust, identifier))

          } recover {
            case _ =>

              // request.userAnswers.set(HasEnrolled, false)
              // Save this to mongo // write it out

              logger.error(s"[Claiming][Session ID: ${Session.id(hc)}] failed to create enrolment for " +
                s"$identifier with tax-enrolments, users credential has not been updated, user needs to claim again")
              InternalServerError(errorHandler.internalServerErrorTemplate)
          }
        }

        lazy val onRelationshipNotFound = Future.successful(Redirect(routes.IsAgentManagingTrustController.onPageLoad(NormalMode)))

        relationshipEstablishment.check(request.internalId, identifier) flatMap {
          case RelationshipFound =>
            onRelationshipFound
          case RelationshipNotFound =>
            logger.warn(s"[Claiming][Session ID: ${Session.id(hc)}] no relationship found in Trust IV," +
              s"cannot continue with enrolling the credential, sending the user back to the start of Trust IV")
            onRelationshipNotFound
        }
        
      } getOrElse {
        logger.warn(s"[Claiming][Session ID: ${Session.id(hc)}] no identifier found in user answers, unable to" +
          s"continue with enrolling credential and claiming the trust on behalf of the user")
        Future.successful(Redirect(routes.SessionExpiredController.onPageLoad()))
      }

  }
}
