// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.model.domain;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.eppcommon.AuthInfo;

import com.googlecode.objectify.annotation.Embed;

/** A version of authInfo specifically for domains. */
@Embed
public class DomainAuthInfo extends AuthInfo {

  public static DomainAuthInfo create(PasswordAuth pw) {
    DomainAuthInfo instance = new DomainAuthInfo();
    instance.pw = pw;
    return instance;
  }

  @Override
  public void verifyAuthorizedFor(EppResource eppResource) throws BadAuthInfoException {
    DomainBase domain = (DomainBase) eppResource;
    checkNotNull(getPw());
    if (getRepoId() != null) {
      // Make sure the repo id matches one of the contacts on the domain.
      ReferenceUnion<ContactResource> foundContact = null;
      for (ReferenceUnion<ContactResource> contact : domain.getReferencedContacts()) {
        String contactRepoId = contact.getLinked().getKey().getName();
        if (getRepoId().equals(contactRepoId)) {
          foundContact = contact;
          break;
        }
      }
      if (foundContact == null) {
        throw new BadAuthInfoException();
      }
      // Check if the password provided matches the password on the referenced contact.
      if (!foundContact.getLinked().get().getAuthInfo().getPw().getValue().equals(
              getPw().getValue())) {
        throw new BadAuthInfoException();
      }
    } else {
      // If not repository ID is specified, then check the password against the domain's password.
      if (!domain.getAuthInfo().getPw().getValue().equals(getPw().getValue())) {
        throw new BadAuthInfoException();
      }
    }
  }
}